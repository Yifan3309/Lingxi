package org.example.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.example.config.RagProperties;
import org.example.web.dto.RelatedNote;
import org.example.web.dto.SourceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索的降级与聚合逻辑：重排阈值过滤、sidecar 掉线时按向量分直出、
 * 相关推荐按笔记去重并排除自身。这些分支依赖"重排存在但会失败"这种组合，
 * 接口测试只有在 sidecar 恰好挂掉时才会碰到，无法稳定断言。
 */
@DisplayName("SemanticSearchService：阈值过滤、降级与按笔记聚合")
class SemanticSearchServiceTest {

    @TempDir
    Path tempDir;

    private EmbeddingStoreService storeService;
    private EmbeddingModel embeddingModel;
    private ObjectProvider<ScoringModel> reranker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(Embedding.from(new float[]{1, 0})));
        storeService = new EmbeddingStoreService(props());
        storeService.addAll(
                List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                List.of(Embedding.from(new float[]{1, 0}),                 // n1 第 0 块：完全命中
                        Embedding.from(new float[]{0.8f, 0.6f}),           // n1 第 1 块
                        Embedding.from(new float[]{0.1f, 1f})),            // n2
                List.of(segment("n1", "笔记一", 0), segment("n1", "笔记一", 1), segment("n2", "笔记二", 0)));
        reranker = mock(ObjectProvider.class);
        when(reranker.getIfAvailable()).thenReturn(null);
    }

    @Test
    @DisplayName("无重排：按向量分数排序并截断到 limit")
    void returnsVectorScoresWithoutReranker() {
        List<SourceRef> hits = service().search("查询", 2);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).noteId()).isEqualTo("n1");
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
    }

    @Test
    @DisplayName("重排：低于 rerank-min-score 的候选被剔除，分数替换为归一化重排分")
    void filtersByRerankMinScore() {
        when(reranker.getIfAvailable()).thenReturn((segments, query) ->
                Response.from(List.of(0.9, 0.1, 0.5)));

        List<SourceRef> hits = service().search("查询", 5);

        assertThat(hits).hasSize(2);                      // 0.1 被 0.3 阈值剔除
        assertThat(hits).extracting(SourceRef::score).containsExactly(0.9, 0.5);
        assertThat(hits).extracting(SourceRef::noteId).containsExactly("n1", "n2");
    }

    @Test
    @DisplayName("重排服务抛异常（sidecar 掉线）：不中断检索，按向量分直出")
    void fallsBackToVectorScoresWhenRerankFails() {
        when(reranker.getIfAvailable()).thenReturn((segments, query) -> {
            throw new IllegalStateException("Connection refused");
        });

        List<SourceRef> hits = service().search("查询", 5);

        assertThat(hits).hasSize(3);
        assertThat(hits.get(0).score()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("相关推荐：排除自身、按笔记去重（多块只留最高分），且不做重排")
    void relatedNotesDedupesAndExcludesSelf() {
        List<RelatedNote> related = service().relatedNotes("n1", "笔记一", "内容", 5);

        assertThat(related).extracting(RelatedNote::noteId).containsExactly("n2");
    }

    @Test
    @DisplayName("分块来源带 noteId/title/chunkIndex/摘要，供前端点击跳转")
    void mapsSourceMetadata() {
        SourceRef hit = service().search("查询", 1).get(0);

        assertThat(hit.noteId()).isEqualTo("n1");
        assertThat(hit.title()).isEqualTo("笔记一");
        assertThat(hit.chunkIndex()).isZero();
        assertThat(hit.excerpt()).doesNotContain("\n");
    }

    private SemanticSearchService service() {
        return new SemanticSearchService(embeddingModel, storeService, props(), reranker);
    }

    private RagProperties props() {
        return new RagProperties(5, tempDir.resolve("embeddings.json").toString(), 300,
                "query: ", "passage: ", true,
                "model.onnx", "tokenizer.json", "http", "http://127.0.0.1:8900",
                4, 0.3, 384, 0, "cpu");
    }

    private static TextSegment segment(String noteId, String title, int chunkIndex) {
        Metadata metadata = Metadata.from(Map.of("note_id", noteId, "title", title));
        metadata.put("chunk_index", chunkIndex);
        return TextSegment.from(title + " 正文\n第二行", metadata);
    }
}
