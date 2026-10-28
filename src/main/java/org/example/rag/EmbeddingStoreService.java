package org.example.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.example.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 向量库门面：持有进程内 InMemoryEmbeddingStore 实例，
 * 每次变更后整库序列化到文件（M1 数据量下开销可忽略）。
 * 换 PgVector/Milvus 时仅需替换本类实现，检索方无感。
 * 附带笔记质心索引（note_id → 各 chunk 向量均值，M3 图谱语义边用）：
 * addAll/removeAllByNoteId 是全部写入入口，索引随之增量维护；启动时从序列化 JSON 重建。
 */
@Service
public class EmbeddingStoreService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStoreService.class);

    private final Path storePath;
    private final InMemoryEmbeddingStore<TextSegment> store;
    private final Object persistLock = new Object();
    private final Map<String, float[]> noteCentroids = new ConcurrentHashMap<>();

    public EmbeddingStoreService(RagProperties props) {
        this.storePath = Path.of(props.storePath());
        if (Files.exists(storePath)) {
            this.store = InMemoryEmbeddingStore.fromFile(storePath);
            rebuildCentroidsFromJson();
            log.info("已从 {} 加载向量库（笔记质心 {} 篇）", storePath, noteCentroids.size());
        } else {
            this.store = new InMemoryEmbeddingStore<>();
            log.info("向量库为空，将在 {} 新建", storePath);
        }
    }

    public InMemoryEmbeddingStore<TextSegment> store() {
        return store;
    }

    /** 笔记质心只读视图：key=noteId。 */
    public Map<String, float[]> noteCentroids() {
        return Map.copyOf(noteCentroids);
    }

    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        store.addAll(ids, embeddings, segments);
        updateCentroids(segments, embeddings);
        persist();
    }

    /**
     * 一篇笔记的向量整体替换（删旧块 + 插新块）——只序列化一次。
     * 调用方原本是 removeAllByNoteId + addAll 两步，每步都整库写盘：10k 块时一次保存写 2×314ms，
     * 且中间态（旧块已删、新块未写）若此刻崩溃会留下残缺库。合并成一次，中间态不再落盘。
     */
    public void replaceAllByNoteId(String noteId, List<String> ids,
                                   List<Embedding> embeddings, List<TextSegment> segments) {
        store.removeAll(metadataKey("note_id").isEqualTo(noteId));
        store.addAll(ids, embeddings, segments);
        noteCentroids.remove(noteId);
        updateCentroids(segments, embeddings);
        persist();
    }

    public void removeAllByNoteId(String noteId) {
        store.removeAll(metadataKey("note_id").isEqualTo(noteId));
        noteCentroids.remove(noteId);
        persist();
    }

    /** 同批按 note_id 分组重算质心（ingest 流程是先 removeAll 再 addAll，此处重算保证幂等）。 */
    private void updateCentroids(List<TextSegment> segments, List<Embedding> embeddings) {
        Map<String, double[]> sums = new ConcurrentHashMap<>();
        Map<String, Integer> counts = new ConcurrentHashMap<>();
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

    /** 无公开迭代 API：经序列化 JSON 重建质心索引（启动一次，容错——失败只降级图谱语义边）。 */
    private void rebuildCentroidsFromJson() {
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(store.serializeToJson());
            Map<String, double[]> sums = new ConcurrentHashMap<>();
            Map<String, Integer> counts = new ConcurrentHashMap<>();
            for (var entry : root.path("entries")) {
                String noteId = entry.path("embedded").path("metadata").path("metadata").path("note_id").asText(null);
                var vector = entry.path("embedding").path("vector");
                if (noteId == null || !vector.isArray() || vector.isEmpty()) {
                    continue;
                }
                double[] sum = sums.computeIfAbsent(noteId, k -> new double[vector.size()]);
                for (int d = 0; d < vector.size(); d++) {
                    sum[d] += vector.get(d).asDouble();
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
        } catch (Exception e) {
            log.warn("笔记质心索引重建失败（图谱语义边将缺失）：{}", String.valueOf(e.getMessage()));
        }
    }

    /**
     * 写盘：先写同目录临时文件，再原子 move 覆盖目标。
     * 直接覆盖目标文件时，序列化中途崩溃/断电会留下半截 JSON，下次启动整库加载失败。
     */
    private void persist() {
        synchronized (persistLock) {
            try {
                if (storePath.getParent() != null) {
                    Files.createDirectories(storePath.getParent());
                }
                Path tmp = storePath.resolveSibling(storePath.getFileName() + ".tmp");
                store.serializeToFile(tmp);
                try {
                    Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException | AccessDeniedException e) {
                    // 部分文件系统（网络盘/被占用）不支持原子替换：退回普通覆盖
                    Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                // 持久化失败不阻断检索链路，但要让问题可见
                throw new UncheckedIOException("向量库写入失败: " + storePath, e);
            }
        }
    }
}
