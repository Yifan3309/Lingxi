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
import java.util.Map;

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
            case "openai-compatible" -> openAiChat(resolveChat(props, runtime), listeners);
            case "ollama" -> ollamaChat(resolveChat(props, runtime), listeners);
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
            case "openai-compatible" -> openAiStreaming(resolveChat(props, runtime), listeners);
            case "ollama" -> ollamaStreaming(resolveChat(props, runtime), listeners);
            default -> throw new IllegalStateException(
                    "未知的 app.ai.chat.provider: " + provider + "，支持 openai-compatible / ollama");
        };
        return new SwappableModels.Streaming(concrete);
    }

    @Bean
    public SwappableModels.Chat visionChatModel(AiProperties props,
                                                RuntimeModelConfig runtime,
                                                TokenUsageLoggingListener listener) {
        AiModelConfig.EffectiveVision v = resolveVision(props, runtime);
        List<ChatModelListener> listeners = List.of(listener);
        ChatModel concrete = OpenAiChatModel.builder()
                .baseUrl(v.baseUrl())
                .apiKey(v.apiKey())
                .modelName(v.model())
                .timeout(props.vision().timeout())
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

    /* ---- 生效配置计算：运行时覆盖 > yml（单一真相源：启动装配、热切换重建、配置展示都必须走这里）---- */

    public record EffectiveChat(String baseUrl, String apiKey, String model, Double temperature, Duration timeout) {}

    /** 视觉模型恒走 OpenAI 兼容端点：baseUrl/apiKey/model 三元组。 */
    public record EffectiveVision(String baseUrl, String apiKey, String model) {}

    public static EffectiveChat resolveChat(AiProperties props, RuntimeModelConfig runtime) {
        AiProperties.Chat cfg = props.chat();
        AiProperties.OpenAiCompatible oc = cfg.openaiCompatible();
        AiProperties.Ollama ol = cfg.ollama();
        String provider = runtime.effectiveChatProvider(cfg.provider());
        boolean ollama = "ollama".equals(provider);
        Map<String, String> slot = runtime.slot(provider);
        // ollama 原生端点挂在根路径（/api/chat）；openai 风格覆盖（以 /v1 结尾）
        // 会把请求打到 /v1/api/chat 直接 404——视作跨 provider 串扰丢弃，退回 yml 的 ollama 地址
        String runtimeBaseUrl = slot.get("baseUrl");
        if (ollama && runtimeBaseUrl != null && runtimeBaseUrl.endsWith("/v1")) {
            runtimeBaseUrl = null;
        }
        return new EffectiveChat(
                firstNonBlank(runtimeBaseUrl, ollama ? ol.baseUrl() : oc.baseUrl()),
                // ollama 无密钥概念：显式置 null（不能走 firstNonBlank——候选全空会抛"模型配置缺失"，曾致 runtime provider=ollama 时启动失败）
                ollama ? null : firstNonBlank(slot.get("apiKey"), oc.apiKey()),
                firstNonBlank(slot.get("model"), ollama ? ol.chatModel() : oc.chatModel()),
                ollama ? ol.temperature() : oc.temperature(),
                ollama ? ol.timeout() : oc.timeout());
    }

    public static EffectiveVision resolveVision(AiProperties props, RuntimeModelConfig runtime) {
        AiProperties.Vision v = props.vision();
        AiProperties.OpenAiCompatible oc = props.chat().openaiCompatible();
        // chat 的运行时地址/密钥是 provider 专属的：chat 在 ollama 时它们指向本地原生端点，
        // 带给 vision（OpenAI 客户端）会打到 ollama 上（模型不存在/路径不对）——与 resolveChat 的守卫同族
        boolean ollama = "ollama".equals(runtime.effectiveChatProvider(props.chat().provider()));
        Map<String, String> slot = runtime.slot(ollama ? "ollama" : "openai-compatible");
        String runtimeBaseUrl = ollama ? null : slot.get("baseUrl");
        String runtimeApiKey = ollama ? null : slot.get("apiKey");
        String baseUrl = firstNonBlank(runtimeBaseUrl, v.baseUrl(), oc.baseUrl());
        String apiKey = firstNonBlank(runtimeApiKey, v.apiKey(), oc.apiKey());
        String model = firstNonBlank(runtime.visionModel(), v.model());
        return new EffectiveVision(baseUrl, apiKey, model);
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
                // 思考模型（qwen3.5 等）默认就会思考：不开 returnThinking，思考增量被 langchain4j 丢弃；
                // 只影响响应解析、不往请求塞 think 参数，非思考模型不受影响
                .returnThinking(true)
                .listeners(listeners)
                .build();
    }

    private StreamingChatModel ollamaStreaming(EffectiveChat c, List<ChatModelListener> listeners) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(c.baseUrl())
                .modelName(c.model())
                .temperature(c.temperature())
                .timeout(c.timeout())
                .returnThinking(true)
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
