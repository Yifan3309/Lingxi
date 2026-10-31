package org.example.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import org.example.config.RagProperties;
import org.example.domain.Note;
import org.example.domain.NoteChunk;
import org.example.repository.NoteChunkRepository;
import org.example.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 向量持久化换轨 note_chunk BLOB 后的门面不变量：
 * ① 向量随行落库（replaceAllByNoteId 回填 BLOB 到本次保存的行）；
 * ② 重启 = 从行重建内存索引与质心（标题回填、孤儿/缺向量行自愈跳过）；
 * ③ 旧 embeddings.json 一次性迁移（按 note_id+text 对位，成功后改名退役）；
 * ④ 删除语义不变：store 条目与质心一起消失（行由调用方删）。
 */
@DisplayName("EmbeddingStoreService：BLOB 持久化与重启重建")
class EmbeddingStoreServiceTest {

    @TempDir
    Path tempDir;

    private NoteChunkRepository chunkRepo;
    private NoteRepository noteRepo;
    private List<NoteChunk> rows;
    private Map<String, Note> notes;

    @BeforeEach
    void setUp() {
        chunkRepo = mock(NoteChunkRepository.class);
        noteRepo = mock(NoteRepository.class);
        rows = new ArrayList<>();
        notes = new HashMap<>();
        when(chunkRepo.findAll()).thenReturn(rows);
        when(chunkRepo.findAllById(any())).thenAnswer(inv -> {
            Map<String, NoteChunk> byId = new HashMap<>();
            rows.forEach(r -> byId.put(r.getId(), r));
            List<NoteChunk> out = new ArrayList<>();
            for (var id : (Iterable<String>) inv.getArgument(0)) {
                NoteChunk r = byId.get(id);
                if (r != null) {
                    out.add(r);
                }
            }
            return out;
        });
        when(chunkRepo.saveAll(any())).thenAnswer(inv -> {
            for (NoteChunk r : (List<NoteChunk>) inv.getArgument(0)) {
                rows.removeIf(x -> x.getId().equals(r.getId())); // JPA saveAll = 按 id upsert
                rows.add(r);
            }
            return null;
        });
        when(noteRepo.findAll()).thenAnswer(inv -> new ArrayList<>(notes.values()));
    }

    private EmbeddingStoreService service() {
        notes.putIfAbsent("n1", note("n1", "笔记n1"));
        return new EmbeddingStoreService(chunkRepo, noteRepo, props());
    }

    private RagProperties props() {
        return new RagProperties(5, tempDir.resolve("embeddings.json").toString(), 300,
                "query: ", "passage: ", true,
                "model.onnx", "tokenizer.json", "http", "http://127.0.0.1:8900",
                4, 0.3, 384, 0, "cpu");
    }

    /** 模拟 ingest 事务：先删该笔记旧行、落不带向量的新行，再走 replaceAllByNoteId 回填 BLOB。 */
    private void ingestLike(EmbeddingStoreService service, String noteId, List<float[]> vectors) {
        rows.removeIf(r -> r.getNoteId().equals(noteId));
        List<String> ids = new ArrayList<>();
        List<Embedding> embs = new ArrayList<>();
        List<TextSegment> segs = new ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            String id = UUID.randomUUID().toString();
            NoteChunk row = new NoteChunk();
            row.setId(id);
            row.setNoteId(noteId);
            row.setChunkIndex(i);
            row.setText("分块 " + i);
            row.setEmbeddingModel("test");
            rows.add(row);
            ids.add(id);
            embs.add(Embedding.from(vectors.get(i)));
            segs.add(segment(noteId, i));
        }
        service.replaceAllByNoteId(noteId, ids, embs, segs);
    }

    private static Note note(String id, String title) {
        Note n = mock(Note.class);
        when(n.getId()).thenReturn(id);
        when(n.getTitle()).thenReturn(title);
        return n;
    }

    private static TextSegment segment(String noteId, int chunkIndex) {
        var metadata = dev.langchain4j.data.document.Metadata.from("note_id", noteId);
        metadata.put("title", "笔记" + noteId);
        metadata.put("chunk_index", chunkIndex);
        return TextSegment.from("分块 " + chunkIndex, metadata);
    }

    private static float[] toVector(byte[] bytes) {
        var buf = ByteBuffer.wrap(bytes);
        float[] v = new float[bytes.length / Float.BYTES];
        buf.asFloatBuffer().get(v);
        return v;
    }

    @Test
    @DisplayName("replaceAllByNoteId：旧块清掉只留新块，BLOB 回填到行，质心随新块重算")
    void replacesAllChunksOfNote() {
        EmbeddingStoreService service = service();

        ingestLike(service, "n1", List.of(new float[]{1, 0}, new float[]{0, 1}));
        assertThat(rows).hasSize(2);

        ingestLike(service, "n1", List.of(new float[]{0.5f, 0.5f}));

        List<NoteChunk> n1Rows = rows.stream().filter(r -> r.getNoteId().equals("n1")).toList();
        assertThat(n1Rows).hasSize(1);
        assertThat(toVector(n1Rows.get(0).getEmbedding())).containsExactly(0.5f, 0.5f);
        assertThat(service.noteCentroids().get("n1")).containsExactly(0.5f, 0.5f);
        assertThat(service.store().search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.5f, 0.5f}))
                .maxResults(10)
                .build()).matches()).hasSize(1);
    }

    @Test
    @DisplayName("重启后从 note_chunk 行重建：向量、质心、标题元数据都恢复")
    void rebuildsFromRowsOnRestart() {
        EmbeddingStoreService first = service();
        ingestLike(first, "n1", List.of(new float[]{1, 0}));

        EmbeddingStoreService restarted = service();

        assertThat(restarted.noteCentroids()).containsKey("n1");
        var matches = restarted.store().search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1, 0}))
                .maxResults(10)
                .build()).matches();
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).embedded().metadata().getString("title")).isEqualTo("笔记n1");
        assertThat(matches.get(0).embedded().metadata().getString("chunk_id")).isEqualTo(matches.get(0).embeddingId());
    }

    @Test
    @DisplayName("重建自愈：已删笔记的孤儿块与缺向量的行跳过，不进检索")
    void rebuildSkipsOrphanAndVectorlessRows() {
        NoteChunk orphan = new NoteChunk();
        orphan.setId(UUID.randomUUID().toString());
        orphan.setNoteId("gone");
        orphan.setChunkIndex(0);
        orphan.setText("孤儿块");
        orphan.setEmbedding(ByteBuffer.allocate(8).putFloat(1).putFloat(0).array());
        orphan.setEmbeddingModel("test");
        rows.add(orphan);

        NoteChunk noVector = new NoteChunk();
        noVector.setId(UUID.randomUUID().toString());
        noVector.setNoteId("n1");
        noVector.setChunkIndex(0);
        noVector.setText("缺向量块");
        noVector.setEmbeddingModel("test");
        rows.add(noVector);

        EmbeddingStoreService service = service();

        assertThat(service.store().search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1, 0}))
                .maxResults(10)
                .build()).matches()).isEmpty();
        assertThat(service.noteCentroids()).doesNotContainKey("gone");
    }

    @Test
    @DisplayName("旧 embeddings.json 一次性迁移：按 note_id+text 对位写 BLOB，原文件改名退役")
    void migratesLegacyJson() throws Exception {
        NoteChunk row = new NoteChunk();
        row.setId(UUID.randomUUID().toString());
        row.setNoteId("n1");
        row.setChunkIndex(0);
        row.setText("分块 0");
        row.setEmbeddingModel("test");
        rows.add(row);

        Path legacy = tempDir.resolve("embeddings.json");
        Files.writeString(legacy, """
                {"entries":[{"embedding":{"vector":[1.0,0.0]},
                "embedded":{"text":"分块 0","metadata":{"metadata":{"note_id":"n1","chunk_index":0}}}}]}
                """);

        EmbeddingStoreService service = service();

        assertThat(toVector(row.getEmbedding())).containsExactly(1.0f, 0.0f);
        assertThat(Files.exists(legacy)).isFalse();
        assertThat(Files.exists(tempDir.resolve("embeddings.json.migrated"))).isTrue();
        assertThat(service.noteCentroids()).containsKey("n1");
    }

    @Test
    @DisplayName("删除整篇笔记：块与质心一起消失")
    void removesNoteCompletely() {
        EmbeddingStoreService service = service();
        ingestLike(service, "n1", List.of(new float[]{1, 0}));

        service.removeAllByNoteId("n1");

        assertThat(service.noteCentroids()).doesNotContainKey("n1");
        assertThat(service.store().search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1, 0}))
                .maxResults(10)
                .build()).matches()).isEmpty();
    }
}
