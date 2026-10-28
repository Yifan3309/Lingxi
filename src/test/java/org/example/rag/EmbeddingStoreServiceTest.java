package org.example.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import org.example.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 向量库门面的两条不变量：
 * ① 一篇笔记的"删旧块+插新块"必须只落盘一次（原实现两步各写一次整库）；
 * ② 落盘必须是原子替换，不能留下半截 JSON 或 .tmp 残留文件。
 * 这两点在接口层看不出来，只能直接对门面断言。
 */
@DisplayName("EmbeddingStoreService：向量替换与原子落盘")
class EmbeddingStoreServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("replaceAllByNoteId：旧块清掉、只留新块，质心随新块重算")
    void replacesAllChunksOfNote() throws IOException {
        EmbeddingStoreService service = new EmbeddingStoreService(props());

        service.addAll(
                List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                List.of(Embedding.from(new float[]{1, 0}), Embedding.from(new float[]{0, 1})),
                List.of(segment("n1", 0), segment("n1", 1)));
        assertThat(entryCount()).isEqualTo(2);

        service.replaceAllByNoteId("n1",
                List.of(UUID.randomUUID().toString()),
                List.of(Embedding.from(new float[]{0.5f, 0.5f})),
                List.of(segment("n1", 0)));

        assertThat(entryCount()).isEqualTo(1);
        assertThat(service.store().search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.5f, 0.5f}))
                .maxResults(10)
                .build()).matches()).hasSize(1);
        float[] centroid = service.noteCentroids().get("n1");
        assertThat(centroid).isNotNull();
        assertThat(centroid[0]).isEqualTo(0.5f);
        assertThat(centroid[1]).isEqualTo(0.5f);
    }

    @Test
    @DisplayName("落盘用临时文件 + 原子替换：目录里不残留 .tmp")
    void persistsAtomically() throws IOException {
        EmbeddingStoreService service = new EmbeddingStoreService(props());
        service.addAll(List.of(UUID.randomUUID().toString()),
                List.of(Embedding.from(new float[]{1, 0})), List.of(segment("n1", 0)));

        assertThat(Files.exists(tempDir.resolve("embeddings.json"))).isTrue();
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList())
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    @DisplayName("重启后从文件恢复：块数与质心都能重建（图谱语义边依赖质心）")
    void rebuildsCentroidsOnRestart() {
        EmbeddingStoreService first = new EmbeddingStoreService(props());
        first.addAll(List.of(UUID.randomUUID().toString()),
                List.of(Embedding.from(new float[]{1, 0})), List.of(segment("n1", 0)));

        EmbeddingStoreService restarted = new EmbeddingStoreService(props());

        assertThat(restarted.noteCentroids()).containsKey("n1");
        assertThat(restarted.store().search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1, 0}))
                .maxResults(10)
                .build()).matches()).hasSize(1);
    }

    @Test
    @DisplayName("删除整篇笔记：块与质心一起消失")
    void removesNoteCompletely() {
        EmbeddingStoreService service = new EmbeddingStoreService(props());
        service.addAll(List.of(UUID.randomUUID().toString()),
                List.of(Embedding.from(new float[]{1, 0})), List.of(segment("n1", 0)));

        service.removeAllByNoteId("n1");

        assertThat(service.noteCentroids()).doesNotContainKey("n1");
        assertThat(service.store().search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1, 0}))
                .maxResults(10)
                .build()).matches()).isEmpty();
    }

    private long entryCount() throws IOException {
        Path file = tempDir.resolve("embeddings.json");
        return new ObjectMapper().readTree(Files.readString(file)).path("entries").size();
    }

    private RagProperties props() {
        return new RagProperties(5, tempDir.resolve("embeddings.json").toString(), 300,
                "query: ", "passage: ", true,
                "model.onnx", "tokenizer.json", "http", "http://127.0.0.1:8900",
                4, 0.3, 384, 0, "cpu");
    }

    private static TextSegment segment(String noteId, int chunkIndex) {
        Metadata metadata = Metadata.from("note_id", noteId);
        metadata.put("title", "笔记" + noteId);
        metadata.put("chunk_index", chunkIndex);
        return TextSegment.from("分块 " + chunkIndex, metadata);
    }
}
