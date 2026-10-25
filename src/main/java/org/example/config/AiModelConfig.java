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
import java.time.Duration;
import java.util.List;

/**
 * 按 app.ai.* 配置 + 运行时覆盖（RuntimeModelConfig）装配模型 Bean。
 * chat/streaming/vision 包在 SwappableModels 里：前端改配置后仅替换内部委托，无需重启。
 */
@Configuration
public class AiModelConfig {

    @Bean
    public SwappableModels.Chat chatModel(AiProperties props,
                                          RuntimeModelConfig runtime,
                                          TokenUsageLoggingListener listener) {
        List<ChatModelListener> listeners = List.of(listener);
        String provider = runtime.effectiveChatProvider(props.chat().provider());
        ChatModel concrete = switch (provider) {
            case "openai-compatible" -> openAiChat(effectiveChat(props, runtime), listeners);
            case "ollama" -> ollamaChat(effectiveChat(props, runtime), listeners);
            default -> throw new IllegalStateException(
                    "未知的 app.ai.chat.provider: " + provider + "，支持 openai-compatible / ollama");
        };
        return new SwappableModels.Chat(concrete);
    }

    @Bean
    public SwappableModels.Streaming streamingChatModel(AiProperties props,
                                                        RuntimeModelConfig runtime,
                                                        TokenUsageLoggingListener listener) {
        List<ChatModelListener> listeners = List.of(listener);
        String provider = runtime.effectiveChatProvider(props.chat().provider());
        StreamingChatModel concrete = switch (provider) {
            case "openai-compatible" -> openAiStreaming(effectiveChat(props, runtime), listeners);
            case "ollama" -> ollamaStreaming(effectiveChat(props, runtime), listeners);
            default -> throw new IllegalStateException(
                    "未知的 app.ai.chat.provider: " + provider + "，支持 openai-compatible / ollama");
        };
        return new SwappableModels.Streaming(concrete);
    }

    @Bean
    public SwappableModels.Chat visionChatModel(AiProperties props,
                                                RuntimeModelConfig runtime,
                                                TokenUsageLoggingListener listener) {
        AiProperties.Vision v = props.vision();
        AiProperties.OpenAiCompatible chat = props.chat().openaiCompatible();
        // 生效值：运行时覆盖 > vision 配置 > chat 配置
        String baseUrl = firstNonBlank(runtime.chatBaseUrl(), v.baseUrl(), chat.baseUrl());
        String apiKey = firstNonBlank(runtime.chatApiKey(), v.apiKey(), chat.apiKey());
        String model = firstNonBlank(runtime.visionModel(), v.model());
        List<ChatModelListener> listeners = List.of(listener);
        ChatModel concrete = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .timeout(v.timeout())
                .listeners(listeners)
                .build();
        return new SwappableModels.Chat(concrete);
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

    /* ---- 生效配置计算：运行时覆盖 > yml ---- */

    record EffectiveChat(String baseUrl, String apiKey, String model, Double temperature, Duration timeout) {}

    private EffectiveChat effectiveChat(AiProperties props, RuntimeModelConfig runtime) {
        AiProperties.Chat cfg = props.chat();
        AiProperties.OpenAiCompatible oc = cfg.openaiCompatible();
        AiProperties.Ollama ol = cfg.ollama();
        boolean ollama = "ollama".equals(runtime.effectiveChatProvider(cfg.provider()));
        return new EffectiveChat(
                firstNonBlank(runtime.chatBaseUrl(), ollama ? ol.baseUrl() : oc.baseUrl()),
                // ollama 无密钥概念：显式置 null（不能走 firstNonBlank——候选全空会抛"模型配置缺失"，曾致 runtime provider=ollama 时启动失败）
                ollama ? null : firstNonBlank(runtime.chatApiKey(), oc.apiKey()),
                firstNonBlank(runtime.chatModel(), ollama ? ol.chatModel() : oc.chatModel()),
                ollama ? ol.temperature() : oc.temperature(),
                ollama ? ol.timeout() : oc.timeout());
    }

    private ChatModel openAiChat(EffectiveChat c, List<ChatModelListener> listeners) {
        return OpenAiChatModel.builder()
                .baseUrl(c.baseUrl())
                .apiKey(c.apiKey())
                .modelName(c.model())
                .temperature(c.temperature())
                .timeout(c.timeout())
                .listeners(listeners)
                .build();
    }

    private StreamingChatModel openAiStreaming(EffectiveChat c, List<ChatModelListener> listeners) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(c.baseUrl())
                .apiKey(c.apiKey())
                .modelName(c.model())
                .temperature(c.temperature())
                .timeout(c.timeout())
                .listeners(listeners)
                .build();
    }

    private ChatModel ollamaChat(EffectiveChat c, List<ChatModelListener> listeners) {
        return OllamaChatModel.builder()
                .baseUrl(c.baseUrl())
                .modelName(c.model())
                .temperature(c.temperature())
                .timeout(c.timeout())
                .listeners(listeners)
                .build();
    }

    private StreamingChatModel ollamaStreaming(EffectiveChat c, List<ChatModelListener> listeners) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(c.baseUrl())
                .modelName(c.model())
                .temperature(c.temperature())
                .timeout(c.timeout())
                .listeners(listeners)
                .build();
    }

    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        throw new IllegalStateException("模型配置缺失：所有候选值均为空");
    }
}
