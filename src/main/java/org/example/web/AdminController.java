package org.example.web;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import org.example.config.AiModelConfig;
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

import java.time.Duration;
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
        // 每 provider 的记忆槽：对话框切 provider 时回填表单用
        Map<String, Object> profilesOut = new LinkedHashMap<>();
        for (String p : List.of("openai-compatible", "ollama")) {
            Map<String, String> s = runtime.slot(p);
            Map<String, Object> prof = new LinkedHashMap<>();
            prof.put("model", s.get("model"));
            prof.put("baseUrl", s.get("baseUrl"));
            if (!"ollama".equals(p)) {
                prof.put("apiKey", s.get("apiKey") == null ? null : mask(s.get("apiKey")));
            }
            profilesOut.put(p, prof);
        }
        out.put("profiles", profilesOut);
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
        // model/baseUrl 原样传递：null = 槽内保持，"" = 清除该覆盖（回退 yml），非空 = 覆盖。
        // 每 provider 一份槽、互不覆盖——切换 provider 只是换激活槽，旧的云端/本地配置都还在。
        String chatModelName = body.chat() == null ? null : body.chat().model();
        String baseUrl = body.chat() == null ? null : body.chat().baseUrl();
        String apiKey = body.chat() == null ? null : blankToNull(body.chat().apiKey());
        String visionModelName = body.vision() == null ? null : blankToNull(body.vision().model());

        if (chatModelName == null && baseUrl == null && apiKey == null
                && visionModelName == null && provider == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有可更新的字段");
        }

        String nextProvider = provider != null ? provider : runtime.effectiveChatProvider(props.chat().provider());
        runtime.save(nextProvider, chatModelName, baseUrl, apiKey,
                visionModelName != null ? visionModelName : runtime.visionModel());

        rebuildChat(nextProvider);
        rebuildVision();
        return config();
    }

    /** 用表单值构建一次性模型发 ping，验证连通性；不触碰 runtime 覆盖与热切换 Bean。 */
    @PostMapping("/config/test")
    public Map<String, Object> testConfig(@RequestBody ModelUpdateRequest body) {
        String provider = body.chat() != null && blankToNull(body.chat().provider()) != null
                ? blankToNull(body.chat().provider())
                : runtime.effectiveChatProvider(props.chat().provider());
        boolean ollama = "ollama".equals(provider);
        AiModelConfig.EffectiveChat c = AiModelConfig.resolveChat(props, runtime);
        String model = pick(body.chat() != null ? body.chat().model() : null, c.model());
        String url = pick(body.chat() != null ? body.chat().baseUrl() : null, c.baseUrl());
        String key = ollama ? null : pick(body.chat() != null ? body.chat().apiKey() : null, c.apiKey());
        long start = System.nanoTime();
        try {
            ChatModel probe = ollama
                    ? OllamaChatModel.builder().baseUrl(url).modelName(model).timeout(Duration.ofSeconds(30)).build()
                    : OpenAiChatModel.builder().baseUrl(url).apiKey(key).modelName(model).timeout(Duration.ofSeconds(30)).build();
            String reply = probe.chat("ping");
            return Map.of("ok", true, "latencyMs", (System.nanoTime() - start) / 1_000_000,
                    "reply", reply == null ? "" : reply);
        } catch (Exception e) {
            return Map.of("ok", false, "latencyMs", (System.nanoTime() - start) / 1_000_000,
                    "error", friendlyTestError(e));
        }
    }

    /** 常见连通性错误的白话提示（测试按钮用于安抚，不求诊断完备）。 */
    private static String friendlyTestError(Exception e) {
        String m = String.valueOf(e.getMessage() == null ? e : e.getMessage());
        String low = m.toLowerCase();
        if (low.contains("connection refused")) {
            return "无法连接（服务未启动或地址不对）";
        }
        if (low.contains("404")) {
            return "地址或模型不存在（检查 base-url 与模型名）";
        }
        if (low.contains("timed out") || low.contains("timeout")) {
            return "连接超时（模型可能在冷加载，稍后再试）";
        }
        if (low.contains("401") || low.contains("403") || low.contains("unauthorized")) {
            return "鉴权失败（检查 api-key）";
        }
        return m;
    }

    private static String pick(String v, String fallback) {
        return v != null && !v.isBlank() ? v.trim() : fallback;
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

    /** 按 effective provider 重建 chat 与流式模型并热替换（进行中的请求继续用旧模型）。
     *  生效值解析必须走 AiModelConfig.resolveChat——与启动装配同一实现，禁止在此重抄一份（/v1 串扰教训）。 */
    private void rebuildChat(String provider) {
        AiModelConfig.EffectiveChat c = AiModelConfig.resolveChat(props, runtime);
        List<ChatModelListener> listeners = List.of(listener);
        if ("ollama".equals(provider)) {
            chatModel.swap(OllamaChatModel.builder()
                    .baseUrl(c.baseUrl()).modelName(c.model())
                    .temperature(c.temperature()).timeout(c.timeout())
                    .returnThinking(true)
                    .listeners(listeners).build());
            streamingChatModel.swap(OllamaStreamingChatModel.builder()
                    .baseUrl(c.baseUrl()).modelName(c.model())
                    .temperature(c.temperature()).timeout(c.timeout())
                    .returnThinking(true)
                    .listeners(listeners).build());
        } else {
            chatModel.swap(OpenAiChatModel.builder()
                    .baseUrl(c.baseUrl()).apiKey(c.apiKey()).modelName(c.model())
                    .temperature(c.temperature()).timeout(c.timeout())
                    .listeners(listeners).build());
            streamingChatModel.swap(OpenAiStreamingChatModel.builder()
                    .baseUrl(c.baseUrl()).apiKey(c.apiKey()).modelName(c.model())
                    .temperature(c.temperature()).timeout(c.timeout())
                    .listeners(listeners).build());
        }
    }

    private void rebuildVision() {
        AiModelConfig.EffectiveVision v = AiModelConfig.resolveVision(props, runtime);
        visionChatModel.swap(OpenAiChatModel.builder()
                .baseUrl(v.baseUrl()).apiKey(v.apiKey()).modelName(v.model())
                .listeners(List.of(listener))
                .timeout(props.vision().timeout()).build());
    }

    /* ---- 脱敏展示 ---- */

    private Map<String, Object> chatConfig() {
        AiProperties.Chat cfg = props.chat();
        String provider = runtime.effectiveChatProvider(cfg.provider());
        // 展示值与实际生效值同源：resolveChat 里含 ollama 的 /v1 串扰守卫，避免"显示的地址模型根本没用"
        AiModelConfig.EffectiveChat c = AiModelConfig.resolveChat(props, runtime);
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("provider", provider);
        chat.put("ymlProvider", cfg.provider());
        chat.put("providers", List.of("openai-compatible", "ollama"));
        chat.put("baseUrl", c.baseUrl());
        chat.put("model", c.model());
        if (!"ollama".equals(provider)) {
            chat.put("apiKey", mask(c.apiKey()));
        }
        Map<String, String> slot = runtime.slot(provider);
        chat.put("overridden", runtime.chatProvider() != null || slot.get("model") != null
                || slot.get("baseUrl") != null
                || (!"ollama".equals(provider) && slot.get("apiKey") != null));
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
