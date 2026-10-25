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
        @DefaultValue("") String passagePrefix,
        @DefaultValue("false") boolean rerankerEnabled,
        @DefaultValue("models/reranker/onnx-communitybge-reranker-v2-m3-ONNX/model_int8.onnx") String rerankerModelPath,
        @DefaultValue("models/reranker/onnx-communitybge-reranker-v2-m3-ONNX/tokenizer.json") String rerankerTokenizerPath,
        /** reranker 实现：onnx = 进程内本地；http = Python sidecar（GPU + 动态批处理，reranker-service/） */
        @DefaultValue("onnx") String rerankerProvider,
        @DefaultValue("http://127.0.0.1:8900") String rerankerUrl,
        @DefaultValue("4") int recallMultiplier,
        @DefaultValue("0.3") double rerankMinScore,
        @DefaultValue("384") int rerankMaxLength,
        @DefaultValue("0") int rerankThreads,
        @DefaultValue("auto") String rerankEp) {
}
