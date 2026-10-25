package org.example.config;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import org.example.rag.EmbeddingStoreService;
import org.example.rag.NoteAssistant;
import org.example.rag.VectorSearchRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 装配：检索器（向量召回 top-k）+ NoteAssistant（AiServices：
 * 流式模型 + 检索器 + 按会话隔离的窗口记忆）。
 */
@Configuration
public class RagConfig {

    @Bean
    public ContentRetriever contentRetriever(EmbeddingStoreService storeService,
                                             EmbeddingModel embeddingModel,
                                             RagProperties props) {
        return new VectorSearchRetriever(embeddingModel, storeService.store(), props.topK(), props.queryPrefix());
    }

    @Bean
    public NoteAssistant noteAssistant(StreamingChatModel streamingChatModel,
                                       ContentRetriever contentRetriever) {
        return AiServices.builder(NoteAssistant.class)
                .streamingChatModel(streamingChatModel)
                .contentRetriever(contentRetriever)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }
}
