package org.example.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.example.config.RagProperties;
import org.example.domain.Note;
import org.example.domain.NoteChunk;
import org.example.repository.NoteChunkRepository;
import org.example.repository.NoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 向量库门面：内存索引（InMemoryEmbeddingStore，暴力余弦）+ H2 行持久化。
 * 向量本体以 BLOB 存进 note_chunk（与正文同库同事务），替代原独立 embeddings.json——
 * 消灭全量重写（每次保存 50MB 级 IO）、正文双份存储与双存储一致性风险；检索路径不变（纯内存）。
 * 启动时从 note_chunk 行重建内存索引与质心；旧 JSON 文件首次启动自动迁移（按 note_id+text 对位）。
 * 附带笔记质心索引（note_id → 各 chunk 向量均值，M3 图谱语义边用）：
 * replaceAllByNoteId/removeAllByNoteId 是全部写入入口，索引随之增量维护。
 * 规模边界：内存常驻到 ~10 万块（约 150MB 向量）；此后迁 JVector（mmap 磁盘索引）。
 */
@Service
public class EmbeddingStoreService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStoreService.class);

    private final NoteChunkRepository chunkRepo;
    private final NoteRepository noteRepo;
    private final Path legacyStorePath;
    private final InMemoryEmbeddingStore<TextSegment> store;
    private final Map<String, float[]> noteCentroids = new ConcurrentHashMap<>();

    public EmbeddingStoreService(NoteChunkRepository chunkRepo, NoteRepository noteRepo, RagProperties props) {
        this.chunkRepo = chunkRepo;
        this.noteRepo = noteRepo;
        this.legacyStorePath = Path.of(props.storePath());
        this.store = new InMemoryEmbeddingStore<>();
        List<NoteChunk> rows = chunkRepo.findAll();
        migrateLegacyFileIfPresent(rows);
        rebuildFromRows(rows);
    }

    public InMemoryEmbeddingStore<TextSegment> store() {
        return store;
    }

    /** 笔记质心只读视图：key=noteId。 */
    public Map<String, float[]> noteCentroids() {
        return Map.copyOf(noteCentroids);
    }

    /**
     * 一篇笔记的向量整体替换（删旧块 + 插新块 + 向量同步进 note_chunk 行）。
     * 调用方（ingest）须已在同一事务内落好不带向量的行——此处回填 BLOB。
     */
    public void replaceAllByNoteId(String noteId, List<String> ids,
                                   List<Embedding> embeddings, List<TextSegment> segments) {
        store.removeAll(metadataKey("note_id").isEqualTo(noteId));
        store.addAll(ids, embeddings, segments);
        noteCentroids.remove(noteId);
        updateCentroids(segments, embeddings);
        Map<String, NoteChunk> rowsById = new HashMap<>();
        chunkRepo.findAllById(ids).forEach(row -> rowsById.put(row.getId(), row));
        List<NoteChunk> touched = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            NoteChunk row = rowsById.get(ids.get(i));
            if (row == null) {
                log.warn("向量回填跳过：条目 {} 无对应 note_chunk 行（正常不应发生）", ids.get(i));
                continue;
            }
            row.setEmbedding(toBytes(embeddings.get(i).vector()));
            touched.add(row);
        }
        chunkRepo.saveAll(touched);
    }

    public void removeAllByNoteId(String noteId) {
        store.removeAll(metadataKey("note_id").isEqualTo(noteId));
        noteCentroids.remove(noteId);
    }

    /** 同批按 note_id 分组重算质心（ingest 流程是先 removeAll 再 addAll，此处重算保证幂等）。 */
    private void updateCentroids(List<TextSegment> segments, List<Embedding> embeddings) {
        Map<String, double[]> sums = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            String noteId = segments.get(i).metadata().getString("note_id");
            if (noteId == null) {
                continue;
            }
            float[] v = embeddings.get(i).vector();
            double[] sum = sums.computeIfAbsent(noteId, k -> new double[v.length]);
            for (int d = 0; d < v.length; d++) {
                sum[d] += v[d];
            }
            counts.merge(noteId, 1, Integer::sum);
        }
        for (var e : sums.entrySet()) {
            int n = counts.get(e.getKey());
            float[] c = new float[e.getValue().length];
            for (int d = 0; d < c.length; d++) {
                c[d] = (float) (e.getValue()[d] / n);
            }
            noteCentroids.put(e.getKey(), c);
        }
    }

    /**
     * 启动重建：note_chunk 行（BLOB 向量 + 正文）→ 内存索引 + 质心。
     * 笔记已删除的孤儿块跳过不进检索（自愈）；缺向量的行跳过（等下次重嵌入）。
     */
    private void rebuildFromRows(List<NoteChunk> rows) {
        Map<String, String> titles = new HashMap<>();
        for (Note n : noteRepo.findAll()) {
            titles.put(n.getId(), n.getTitle());
        }
        List<String> ids = new ArrayList<>();
        List<Embedding> embeddings = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();
        int orphan = 0;
        int noVector = 0;
        Map<String, double[]> sums = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (NoteChunk row : rows) {
            if (row.getEmbedding() == null) {
                noVector++;
                continue;
            }
            String title = titles.get(row.getNoteId());
            if (title == null) {
                // 笔记行不存在 = 孤儿块（已删笔记的残留），跳过自愈；
                // 笔记存在但 title 为 null 时用占位符，不能误判成孤儿丢掉检索
                if (!titles.containsKey(row.getNoteId())) {
                    orphan++;
                    continue;
                }
                title = "未命名";
            }
            var md = dev.langchain4j.data.document.Metadata.from("note_id", row.getNoteId());
            md.put("chunk_id", row.getId());
            md.put("title", title);
            md.put("chunk_index", row.getChunkIndex());
            float[] vector = toVector(row.getEmbedding());
            ids.add(row.getId());
            embeddings.add(Embedding.from(vector));
            segments.add(TextSegment.from(row.getText(), md));
            double[] sum = sums.computeIfAbsent(row.getNoteId(), k -> new double[vector.length]);
            for (int d = 0; d < vector.length; d++) {
                sum[d] += vector[d];
            }
            counts.merge(row.getNoteId(), 1, Integer::sum);
        }
        store.addAll(ids, embeddings, segments);
        for (var e : sums.entrySet()) {
            int n = counts.get(e.getKey());
            float[] c = new float[e.getValue().length];
            for (int d = 0; d < c.length; d++) {
                c[d] = (float) (e.getValue()[d] / n);
            }
            noteCentroids.put(e.getKey(), c);
        }
        log.info("向量库从 note_chunk 重建：{} 块 / 质心 {} 篇（孤儿块 {}、缺向量 {}）",
                ids.size(), noteCentroids.size(), orphan, noVector);
    }

    /**
     * 旧版独立向量文件（embeddings.json）一次性迁移：按 note_id+text 对位到 note_chunk 行，
     * 匹配不上的丢弃（下次重嵌入自愈），成功后原文件改名退役。
     */
    private void migrateLegacyFileIfPresent(List<NoteChunk> rows) {
        if (!Files.exists(legacyStorePath)) {
            return;
        }
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(legacyStorePath));
            Map<String, NoteChunk> byKey = new HashMap<>();
            for (NoteChunk r : rows) {
                byKey.put(r.getNoteId() + "\u0000" + r.getText(), r);
            }
            int matched = 0;
            int dropped = 0;
            List<NoteChunk> touched = new ArrayList<>();
            for (var entry : root.path("entries")) {
                String noteId = entry.path("embedded").path("metadata").path("metadata").path("note_id").asText(null);
                String text = entry.path("embedded").path("text").asText(null);
                var vector = entry.path("embedding").path("vector");
                if (noteId == null || text == null || !vector.isArray() || vector.isEmpty()) {
                    dropped++;
                    continue;
                }
                NoteChunk row = byKey.get(noteId + "\u0000" + text);
                if (row == null) {
                    dropped++;
                    continue;
                }
                float[] v = new float[vector.size()];
                for (int d = 0; d < v.length; d++) {
                    v[d] = (float) vector.get(d).asDouble();
                }
                row.setEmbedding(toBytes(v));
                touched.add(row);
                matched++;
            }
            if (matched > 0) {
                chunkRepo.saveAll(touched);
            }
            Files.move(legacyStorePath, legacyStorePath.resolveSibling(legacyStorePath.getFileName() + ".migrated"),
                    StandardCopyOption.REPLACE_EXISTING);
            log.info("旧向量文件已迁移进 note_chunk：匹配 {} 条、丢弃 {} 条（无对应分块，重嵌入自愈）", matched, dropped);
        } catch (Exception e) {
            log.warn("旧向量文件迁移失败（保留原文件，重启可重试）：{}", String.valueOf(e.getMessage()));
        }
    }

    private static byte[] toBytes(float[] vector) {
        var buf = java.nio.ByteBuffer.allocate(vector.length * Float.BYTES);
        buf.asFloatBuffer().put(vector);
        return buf.array();
    }

    private static float[] toVector(byte[] bytes) {
        var buf = java.nio.ByteBuffer.wrap(bytes);
        float[] v = new float[bytes.length / Float.BYTES];
        buf.asFloatBuffer().get(v);
        return v;
    }
}
