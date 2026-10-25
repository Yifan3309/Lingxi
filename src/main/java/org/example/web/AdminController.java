package org.example.web;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import org.example.config.AiProperties;
import org.example.config.RuntimeModelConfig;
import org.example.config.SwappableModels;
import org.example.observability.TokenUsageLoggingListener;
import org.example.rag.ReembedService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型配置查看与热切换：POST 更新 chat（provider/模型/地址/密钥）与 vision 模型，
 * 运行时覆盖持久化到 data/runtime-config.json（重启仍生效），Swappable 委托即时生效。
 * provider 切换语义：切换时自动清空旧的 baseUrl/apiKey/model 覆盖（防跨 provider 串扰，
 * 如旧云端地址被带进 Ollama），本次请求同时提交的新值照常生效。
 * 嵌入模型刻意不开放热切换——换嵌入模型向量空间失效需全量重嵌入（风险 R1）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AiProperties props;
    private final RuntimeModelConfig runtime;
    private final SwappableModels.Chat chatModel;
    private final SwappableModels.Streaming streamingChatModel;
    private final SwappableModels.Chat visionChatModel;
    private final TokenUsageLoggingListener listener;
    private final ReembedService reembedService;

    public AdminController(AiProperties props,
                           RuntimeModelConfig runtime,
                           SwappableModels.Chat chatModel,
                           SwappableModels.Streaming streamingChatModel,
                           SwappableModels.Chat visionChatModel,
                           TokenUsageLoggingListener listener,
                           ReembedService reembedService) {
        this.props = props;
        this.runtime = runtime;
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.visionChatModel = visionChatModel;
        this.listener = listener;
        this.reembedService = reembedService;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chat", chatConfig());
        out.put("embedding", embeddingConfig());
        out.put("vision", Map.of(
                "model", effectiveVisionModel(),
                "overridden", runtime.visionModel() != null));
        return out;
    }

    public record ModelUpdateRequest(ChatUpdate chat, VisionUpdate vision) {

        public record ChatUpdate(String provider, String model, String baseUrl, String apiKey) {
        }

        public record VisionUpdate(String model) {
        }
    }

    @PostMapping("/config")
    public Map<String, Object> update(@RequestBody ModelUpdateRequest body) {
        String provider = body.chat() == null ? null : blankToNull(body.chat().provider());
        if (provider != null && !"openai-compatible".equals(provider) && !"ollama".equals(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的 provider: " + provider + "（可选 openai-compatible / ollama）");
        }
        String chatModelName = body.chat() == null ? null : blankToNull(body.chat().model());
        String baseUrl = body.chat() == null ? null : blankToNull(body.chat().baseUrl());
        String apiKey = body.chat() == null ? null : blankToNull(body.chat().apiKey());
        String visionModelName = body.vision() == null ? null : blankToNull(body.vision().model());

        String prevProvider = runtime.effectiveChatProvider(props.chat().provider());
        String nextProvider = provider != null ? provider : prevProvider;
        boolean providerChanged = !nextProvider.equals(prevProvider);

        if (chatModelName == null && baseUrl == null && apiKey == null
                && visionModelName == null && provider == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有可更新的字段");
        }

        // 切换 provider 时清空跨 provider 的旧覆盖（除非本次请求带了新值）——地址/密钥/模型名都是 provider 专属
        String savedModel = providerChanged && chatModelName == null ? null
                : chatModelName != null ? chatModelName : runtime.chatModel();
        String savedBaseUrl = providerChanged && baseUrl == null ? null
                : baseUrl != null ? baseUrl : runtime.chatBaseUrl();
        String savedApiKey = providerChanged && apiKey == null ? null
                : apiKey != null ? apiKey : runtime.chatApiKey();
        runtime.save(nextProvider, savedModel, savedBaseUrl, savedApiKey,
                visionModelName != null ? visionModelName : runtime.visionModel());

        rebuildChat(nextProvider);
        rebuildVision();
        return config();
    }

    /* ---- 全量重嵌入维护（R1 对策，M3） ---- */

    @PostMapping("/reembed")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReembedService.ReembedStatus reembed() {
        if (!reembedService.tryStart()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "重建已在进行中");
        }
        reembedService.reembedAll();
        return reembedService.status();
    }

    @GetMapping("/reembed/status")
    public ReembedService.ReembedStatus reembedStatus() {
        return reembedService.status();
    }

    /** 按 effective provider 重建 chat 与流式模型并热替换（进行中的请求继续用旧模型）。 */
    private void rebuildChat(String provider) {
        AiProperties.OpenAiCompatible oc = props.chat().openaiCompatible();
        AiProperties.Ollama ol = props.chat().ollama();
        boolean ollama = "ollama".equals(provider);
        String baseUrl = firstNonBlank(runtime.chatBaseUrl(), ollama ? ol.baseUrl() : oc.baseUrl());
        String apiKey = firstNonBlank(runtime.chatApiKey(), ollama ? null : oc.apiKey());
        String model = firstNonBlank(runtime.chatModel(), ollama ? ol.chatModel() : oc.chatModel());
        List<ChatModelListener> listeners = List.of(listener);
        if (ollama) {
            chatModel.swap(OllamaChatModel.builder()
                    .baseUrl(baseUrl).modelName(model)
                    .temperature(ol.temperature()).timeout(ol.timeout())
                    .listeners(listeners).build());
            streamingChatModel.swap(OllamaStreamingChatModel.builder()
                    .baseUrl(baseUrl).modelName(model)
                    .temperature(ol.temperature()).timeout(ol.timeout())
                    .listeners(listeners).build());
        } else {
            chatModel.swap(OpenAiChatModel.builder()
                    .baseUrl(baseUrl).apiKey(apiKey).modelName(model)
                    .temperature(oc.temperature()).timeout(oc.timeout())
                    .listeners(listeners).build());
            streamingChatModel.swap(OpenAiStreamingChatModel.builder()
                    .baseUrl(baseUrl).apiKey(apiKey).modelName(model)
                    .temperature(oc.temperature()).timeout(oc.timeout())
                    .listeners(listeners).build());
        }
    }

    private void rebuildVision() {
        AiProperties.Vision v = props.vision();
        AiProperties.OpenAiCompatible oc = props.chat().openaiCompatible();
        String visionName = firstNonBlank(runtime.visionModel(), v.model());
        String visionBase = firstNonBlank(runtime.chatBaseUrl(), v.baseUrl(), oc.baseUrl());
        String visionKey = firstNonBlank(runtime.chatApiKey(), v.apiKey(), oc.apiKey());
        visionChatModel.swap(OpenAiChatModel.builder()
                .baseUrl(visionBase).apiKey(visionKey).modelName(visionName)
                .listeners(List.of(listener))
                .timeout(v.timeout()).build());
    }

    /* ---- 脱敏展示 ---- */

    private Map<String, Object> chatConfig() {
        AiProperties.Chat cfg = props.chat();
        String provider = runtime.effectiveChatProvider(cfg.provider());
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("provider", provider);
        chat.put("ymlProvider", cfg.provider());
        chat.put("providers", List.of("openai-compatible", "ollama"));
        if ("ollama".equals(provider)) {
            chat.put("baseUrl", effective(cfg.ollama().baseUrl(), runtime.chatBaseUrl()));
            chat.put("model", effective(cfg.ollama().chatModel(), runtime.chatModel()));
        } else {
            AiProperties.OpenAiCompatible oc = cfg.openaiCompatible();
            chat.put("baseUrl", effective(oc.baseUrl(), runtime.chatBaseUrl()));
            chat.put("model", effective(oc.chatModel(), runtime.chatModel()));
            chat.put("apiKey", mask(effective(oc.apiKey(), runtime.chatApiKey())));
        }
        chat.put("overridden", runtime.chatProvider() != null || runtime.chatModel() != null
                || runtime.chatBaseUrl() != null || runtime.chatApiKey() != null);
        return chat;
    }

    private String effectiveVisionModel() {
        return firstNonBlank(runtime.visionModel(), props.vision().model());
    }

    private Map<String, Object> embeddingConfig() {
        AiProperties.Embedding cfg = props.embedding();
        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("provider", cfg.provider());
        switch (cfg.provider()) {
            case "ollama" -> {
                embedding.put("baseUrl", cfg.ollama().baseUrl());
                embedding.put("model", cfg.ollama().embeddingModel());
            }
            case "onnx" -> {
                embedding.put("modelPath", cfg.onnx().modelPath());
                embedding.put("poolingMode", cfg.onnx().poolingMode());
            }
            default -> {
                embedding.put("baseUrl", cfg.openaiCompatible().baseUrl());
                embedding.put("model", cfg.openaiCompatible().embeddingModel());
                embedding.put("apiKey", mask(cfg.openaiCompatible().apiKey()));
            }
        }
        embedding.put("note", "嵌入模型不支持热切换（更换需全量重嵌入，见文档 R1）");
        return embedding;
    }

    private static String effective(String ymlValue, String override) {
        return override != null ? override : ymlValue;
    }

    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private static String mask(String key) {
        if (key == null || key.length() < 8) {
            return "***";
        }
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
