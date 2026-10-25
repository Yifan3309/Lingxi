package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * RAG 链路配置。queryPrefix/passagePrefix 为模型输入约定
 * （E5 系列必须 "query: "/"passage: "；BGE、qwen3-embedding 留空）。
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        @DefaultValue("5") int topK,
        @DefaultValue("data/embeddings.json") String storePath,
        @DefaultValue("800") int chunkTargetChars,
        @DefaultValue("") String queryPrefix,
        @DefaultValue("") String passagePrefix) {
}
