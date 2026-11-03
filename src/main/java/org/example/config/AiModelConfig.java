package org.example.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ResponseFormat;
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

    /** 概念抽取的输出上限：只返回名字数组，512 足够，无上限等于允许无限生成 */
    private static final int CONCEPT_NUM_PREDICT = 512;
    /** 主题分组要返回几十个成员名，512 不够，给 2048 */
    private static final int THEME_NUM_PREDICT = 2048;
    /**
     * 概念抽取超时。取 60s 而不是对话那档 120s：抽取正常 1 秒，
     * 到这个量级基本是模型在异常生成，宁可失败留空也不要占住单线程队列。
     */
    private static final Duration CONCEPT_TIMEOUT = Duration.ofSeconds(60);

    @Bean
    public SwappableModels.Chat chatModel(AiProperties props,
                                          RuntimeModelConfig runtime,
                                          TokenUsageLoggingListener listener) {
        String provider = runtime.effectiveChatProvider(props.chat().provider());
        return new SwappableModels.Chat(chatModelOf(provider, resolveChat(props, runtime), List.of(listener)));
    }

    @Bean
    public SwappableModels.Streaming streamingChatModel(AiProperties props,
                                                        RuntimeModelConfig runtime,
                                                        TokenUsageLoggingListener listener) {
        String provider = runtime.effectiveChatProvider(props.chat().provider());
        return new SwappableModels.Streaming(
                streamingModelOf(provider, resolveChat(props, runtime), List.of(listener)));
    }

    /**
     * 对话模型构建的<b>唯一实现</b>：启动装配、热切换（AdminController.rebuildChat）、
     * 连通性 ping 三处都必须走这里。
     *
     * <p>曾经三处各写一份，云端分支漏了 {@code returnThinking} 而本地分支有——表现为
     * 「Ollama 有思考、云端没有」，且只改启动装配时热切换一次配置思考又消失。
     * 参数语义与 {@link #resolveChat} 同理：复制实现必然漂移，禁止再各写一份。
     */
    public static ChatModel chatModelOf(String provider, EffectiveChat c, List<ChatModelListener> listeners) {
        return switch (provider) {
            case "openai-compatible" -> openAiChat(c, listeners);
            case "ollama" -> ollamaChat(c, listeners);
            default -> throw new IllegalStateException(
                    "未知的 app.ai.chat.provider: " + provider + "，支持 openai-compatible / ollama");
        };
    }

    /** 流式对话模型构建的唯一实现，语义同 {@link #chatModelOf}。 */
    public static StreamingChatModel streamingModelOf(String provider, EffectiveChat c,
                                                      List<ChatModelListener> listeners) {
        return switch (provider) {
            case "openai-compatible" -> openAiStreaming(c, listeners);
            case "ollama" -> ollamaStreaming(c, listeners);
            default -> throw new IllegalStateException(
                    "未知的 app.ai.chat.provider: " + provider + "，支持 openai-compatible / ollama");
        };
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

    /**
     * 概念抽取专用模型。<b>模型与对话相同，但输出预算收紧、并且显式关掉思考。</b>
     *
     * <p>实测（qwen_3.5_2BQ4）：对话模型是思考模型、没设输出上限、上下文用模型默认的 262k。
     * 拿它做抽取时思考会一直生成、content 为空，单篇跑满 120s 超时后 langchain4j 还会自动重试，
     * 单篇近 3 分钟才失败一次；线程栈停在 {@code OllamaChatModel.chat}，队列看起来完全卡死
     * （只抽完 1 篇、积压 33 篇、概念数 0）。收紧后单篇约 1 秒。
     *
     * <p><b>关键：思考要"不产生"，而不是"产生了不用"。</b>OpenAI 兼容端点的 {@code max_tokens}
     * 是<b>生成总预算</b>，reasoning token 与正文共用它——只把 {@code returnThinking} 关掉，
     * 思考一样消耗预算，512 照样被吃空、content 返回空字符串。所以必须在请求侧就关：
     *
     * <ul>
     *   <li>Ollama：原生的 {@code think(false)}</li>
     *   <li>云端：{@code app.ai.extract.disable-thinking} 里的厂商参数，经 {@code customParameters}
     *       原样拼进请求体（Jackson 的 {@code @JsonAnyGetter}，见 ChatCompletionRequest）。
     *       字段名各家不同——智谱/GLM 是 {@code thinking.type=disabled}，千问兼容端点是
     *       {@code enable_thinking=false}——所以做成配置而不是写死某一家的。</li>
     * </ul>
     *
     * <p><b>刻意不设 numCtx。</b>抽取值 8192、对话用模型默认（这个模型是 262144），
     * 两者交替运行时 Ollama 会为每一边重新加载模型——实测单次 70–100 s，
     * 用户发一条消息就要等一分半。抽取不该改变已加载的上下文，让它跟对话共用同一个实例，
     * 代价只是 KV cache 大一些，换来的是不会互相触发重载。
     *
     * 另外固定 temperature 0 并要 JSON 输出：抽取要的是可复现，不是多样。
     */
    @Bean("conceptChatModel")
    public ChatModel conceptChatModel(AiProperties props,
                                      RuntimeModelConfig runtime,
                                      TokenUsageLoggingListener listener) {
        return extractModelOf(runtime.effectiveChatProvider(props.chat().provider()),
                resolveChat(props, runtime), CONCEPT_NUM_PREDICT, disableThinkingParams(props), List.of(listener));
    }

    /**
     * 主题分组专用模型：与抽取同一个坑（思考模型、无输出上限），但输出预算更大。
     *
     * <p>抽取只返回一个名字数组（几十个 token），主题分组要返回几十个成员名，
     * 512 不够用，这里给 2048。
     */
    @Bean("themeChatModel")
    public ChatModel themeChatModel(AiProperties props,
                                    RuntimeModelConfig runtime,
                                    TokenUsageLoggingListener listener) {
        return extractModelOf(runtime.effectiveChatProvider(props.chat().provider()),
                resolveChat(props, runtime), THEME_NUM_PREDICT, disableThinkingParams(props), List.of(listener));
    }

    /** 云端抽取要拼进请求体的"别思考"参数；没配就是空 Map（不发送任何额外字段）。 */
    public static Map<String, Object> disableThinkingParams(AiProperties props) {
        AiProperties.Extract ex = props.extract();
        return ex == null || ex.disableThinking() == null ? Map.of() : ex.disableThinking();
    }

    /**
     * 后台抽取类模型的唯一构建入口：概念抽取（512）与主题分组（2048）只差输出预算。
     *
     * <p>与 {@link #chatModelOf} 分开不是因为风格：这里必须固定 temperature 0、JSON 输出、
     * 60s 超时，而且一定要关掉思考——这些参数被误改会直接让抽取挂死或返回空，
     * 而对话模型的参数是给人调的。
     *
     * @param vendorParams 云端用于关闭思考的厂商参数，原样并入请求体；Ollama 分支忽略它（走 think(false)）
     */
    public static ChatModel extractModelOf(String provider, EffectiveChat c, int maxTokens,
                                           Map<String, Object> vendorParams, List<ChatModelListener> listeners) {
        return switch (provider) {
            case "ollama" -> OllamaChatModel.builder()
                    .baseUrl(c.baseUrl())
                    .modelName(c.model())
                    .temperature(0.0)
                    .think(false)
                    .numPredict(maxTokens)
                    .responseFormat(ResponseFormat.JSON)
                    .timeout(CONCEPT_TIMEOUT)
                    .listeners(listeners)
                    .build();
            case "openai-compatible" -> OpenAiChatModel.builder()
                    .baseUrl(c.baseUrl())
                    .apiKey(c.apiKey())
                    .modelName(c.model())
                    .temperature(0.0)
                    // returnThinking(false) 只是不把思考交给我们，思考本身照常生成、照常占预算；
                    // 真正让思考不占预算的是 vendorParams（请求侧关闭）。
                    .returnThinking(false)
                    .customParameters(vendorParams == null ? Map.of() : vendorParams)
                    .maxTokens(maxTokens)
                    .responseFormat(ResponseFormat.JSON)
                    .timeout(CONCEPT_TIMEOUT)
                    .listeners(listeners)
                    .build();
            default -> throw new IllegalStateException(
                    "未知的 app.ai.chat.provider: " + provider + "，支持 openai-compatible / ollama");
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

    private static ChatModel openAiChat(EffectiveChat c, List<ChatModelListener> listeners) {
        return OpenAiChatModel.builder()
                .baseUrl(c.baseUrl())
                .apiKey(c.apiKey())
                .modelName(c.model())
                .temperature(c.temperature())
                .timeout(c.timeout())
                // 与 ollama 分支对称：OpenAI 兼容端点把思考放在响应的 reasoning_content 字段，
                // 不开这个开关 langchain4j 会静默丢弃（ChatCompletionEventDispatcher 按此布尔门控
                // onPartialThinking，非流式则看 OpenAiUtils.aiMessageFrom(response, returnThinking)）。
                // 本地 qwen3 会思考、云端 mimo/GLM-4.5/deepseek-reasoner 也会，之前只有本地开了，
                // 表现为「Ollama 有折叠思考框、云端没有」。
                // returnThinking 只影响响应解析，不往请求塞任何参数。智谱 thinking.type、Qwen
                // enable_thinking 这类「请求侧开思考」的厂商字段，langchain4j 只能靠 customParameters
                // 注入（本项目尚未开放该配置项）；模型默认思考的端点不需要它。
                .returnThinking(true)
                .listeners(listeners)
                .build();
    }

    private static StreamingChatModel openAiStreaming(EffectiveChat c, List<ChatModelListener> listeners) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(c.baseUrl())
                .apiKey(c.apiKey())
                .modelName(c.model())
                .temperature(c.temperature())
                .timeout(c.timeout())
                .returnThinking(true)
                .listeners(listeners)
                .build();
    }

    private static ChatModel ollamaChat(EffectiveChat c, List<ChatModelListener> listeners) {
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

    private static StreamingChatModel ollamaStreaming(EffectiveChat c, List<ChatModelListener> listeners) {
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
