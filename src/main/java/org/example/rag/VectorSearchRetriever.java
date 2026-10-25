package org.example.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.List;
import java.util.Map;

/**
 * 自实现向量检索器：查询文本加前缀后向量化，取 top-k，附分数与分块 id 元数据。
 * 自写而非用 EmbeddingStoreContentRetriever，是为了在查询侧挂模型约定的前缀
 * （如 E5 系列要求 "query: "）而不必包装整个 EmbeddingModel 接口。
 */
public class VectorSearchRetriever implements ContentRetriever {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final int topK;
    private final String queryPrefix;

    public VectorSearchRetriever(EmbeddingModel embeddingModel,
                                 EmbeddingStore<TextSegment> embeddingStore,
                                 int topK,
                                 String queryPrefix) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.topK = topK;
        this.queryPrefix = queryPrefix == null ? "" : queryPrefix;
    }

    @Override
    public List<Content> retrieve(Query query) {
        Embedding queryEmbedding = embeddingModel.embed(queryPrefix + query.text()).content();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .build());
        return result.matches().stream()
                .map(match -> Content.from(match.embedded(), Map.of(
                        ContentMetadata.SCORE, match.score(),
                        ContentMetadata.EMBEDDING_ID, match.embeddingId())))
                .toList();
    }
}
