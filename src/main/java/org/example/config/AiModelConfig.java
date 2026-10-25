package org.example.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.example.observability.TokenUsageLoggingListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/**
 * 按 app.ai.* 配置装配模型 Bean（M0）。
 * 业务代码只依赖 ChatModel / StreamingChatModel / EmbeddingModel 接口，
 * 切换 provider 仅需改配置文件后重启。
 */
@Configuration
public class AiModelConfig {

    @Bean
    public ChatModel chatModel(AiProperties props, TokenUsageLoggingListener listener) {
        AiProperties.Chat cfg = props.chat();
        List<ChatModelListener> listeners = List.of(listener);
        return switch (cfg.provider()) {
            case "openai-compatible" -> OpenAiChatModel.builder()
                    .baseUrl(cfg.openaiCompatible().baseUrl())
                    .apiKey(cfg.openaiCompatible().apiKey())
                    .modelName(cfg.openaiCompatible().chatModel())
                    .temperature(cfg.openaiCompatible().temperature())
                    .timeout(cfg.openaiCompatible().timeout())
                    .listeners(listeners)
                    .build();
            case "ollama" -> OllamaChatModel.builder()
                    .baseUrl(cfg.ollama().baseUrl())
                    .modelName(cfg.ollama().chatModel())
                    .temperature(cfg.ollama().temperature())
                    .timeout(cfg.ollama().timeout())
                    .listeners(listeners)
                    .build();
            default -> throw new IllegalStateException(
                    "未知的 app.ai.chat.provider: " + cfg.provider() + "，支持 openai-compatible / ollama");
        };
    }

    @Bean
    public StreamingChatModel streamingChatModel(AiProperties props, TokenUsageLoggingListener listener) {
        AiProperties.Chat cfg = props.chat();
        List<ChatModelListener> listeners = List.of(listener);
        return switch (cfg.provider()) {
            case "openai-compatible" -> OpenAiStreamingChatModel.builder()
                    .baseUrl(cfg.openaiCompatible().baseUrl())
                    .apiKey(cfg.openaiCompatible().apiKey())
                    .modelName(cfg.openaiCompatible().chatModel())
                    .temperature(cfg.openaiCompatible().temperature())
                    .timeout(cfg.openaiCompatible().timeout())
                    .listeners(listeners)
                    .build();
            case "ollama" -> OllamaStreamingChatModel.builder()
                    .baseUrl(cfg.ollama().baseUrl())
                    .modelName(cfg.ollama().chatModel())
                    .temperature(cfg.ollama().temperature())
                    .timeout(cfg.ollama().timeout())
                    .listeners(listeners)
                    .build();
            default -> throw new IllegalStateException(
                    "未知的 app.ai.chat.provider: " + cfg.provider() + "，支持 openai-compatible / ollama");
        };
    }

    @Bean
    public EmbeddingModel embeddingModel(AiProperties props) {
        AiProperties.Embedding cfg = props.embedding();
        return switch (cfg.provider()) {
            case "openai-compatible" -> OpenAiEmbeddingModel.builder()
                    .baseUrl(cfg.openaiCompatible().baseUrl())
                    .apiKey(cfg.openaiCompatible().apiKey())
                    .modelName(cfg.openaiCompatible().embeddingModel())
                    .timeout(cfg.openaiCompatible().timeout())
                    .build();
            case "ollama" -> OllamaEmbeddingModel.builder()
                    .baseUrl(cfg.ollama().baseUrl())
                    .modelName(cfg.ollama().embeddingModel())
                    .timeout(cfg.ollama().timeout())
                    .build();
            case "onnx" -> new OnnxEmbeddingModel(
                    Path.of(cfg.onnx().modelPath()),
                    Path.of(cfg.onnx().tokenizerPath()),
                    PoolingMode.valueOf(cfg.onnx().poolingMode()));
            default -> throw new IllegalStateException(
                    "未知的 app.ai.embedding.provider: " + cfg.provider() + "，支持 openai-compatible / ollama / onnx");
        };
    }
}

