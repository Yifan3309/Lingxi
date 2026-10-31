package org.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 运行时模型覆盖（前端可改）：独立于 application.yml 的生效层。
 * 覆盖值持久化到 data/runtime-config.json（gitignored），重启后仍生效；
 * 字段为 null = 用 yml 配置。
 */
@Service
public class RuntimeModelConfig {

    private static final Logger log = LoggerFactory.getLogger(RuntimeModelConfig.class);
    private static final Path FILE = Path.of("data/runtime-config.json").toAbsolutePath().normalize();

    /** 对话模型名（chat 与流式共用） */
    private volatile String chatModel;
    private volatile String chatBaseUrl;
    private volatile String chatApiKey;
    /** 对话 provider 覆盖（openai-compatible | ollama；null = 跟随 yml） */
    private volatile String chatProvider;
    /** 视觉模型名 */
    private volatile String visionModel;

    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    void load() {
        if (!Files.exists(FILE)) {
            return;
        }
        try {
            Map<String, Object> json = mapper.readValue(FILE.toFile(), Map.class);
            chatModel = str(json.get("chatModel"));
            chatBaseUrl = str(json.get("chatBaseUrl"));
            chatApiKey = str(json.get("chatApiKey"));
            chatProvider = str(json.get("chatProvider"));
            visionModel = str(json.get("visionModel"));
            log.info("已加载运行时模型覆盖：chatProvider={}, chatModel={}, visionModel={}", chatProvider, chatModel, visionModel);
        } catch (IOException e) {
            log.warn("运行时模型覆盖读取失败（忽略，使用 yml 配置）：{}", String.valueOf(e.getMessage()));
        }
    }

    public synchronized void save(String chatProvider, String chatModel, String chatBaseUrl, String chatApiKey, String visionModel) {
        apply(chatProvider, chatModel, chatBaseUrl, chatApiKey, visionModel);
        try {
            Files.createDirectories(FILE.getParent());
            Map<String, Object> json = new java.util.LinkedHashMap<>();
            json.put("chatProvider", this.chatProvider);
            json.put("chatModel", this.chatModel);
            json.put("chatBaseUrl", this.chatBaseUrl);
            json.put("chatApiKey", this.chatApiKey);
            json.put("visionModel", this.visionModel);
            // 原子落盘（与 EmbeddingStoreService 同约定）：序列化中途崩溃不留半截 JSON
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), json);
            try {
                Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("运行时模型覆盖写入失败（内存中仍生效）：{}", String.valueOf(e.getMessage()));
        }
    }

    /** 只改内存生效值（不落盘）：包内可见，供单测构造覆盖状态。 */
    synchronized void apply(String chatProvider, String chatModel, String chatBaseUrl, String chatApiKey, String visionModel) {
        this.chatProvider = blankToNull(chatProvider);
        this.chatModel = blankToNull(chatModel);
        this.chatBaseUrl = blankToNull(chatBaseUrl);
        this.chatApiKey = blankToNull(chatApiKey);
        this.visionModel = blankToNull(visionModel);
    }

    public String chatProvider() { return chatProvider; }
    public String chatModel() { return chatModel; }
    public String chatBaseUrl() { return chatBaseUrl; }
    public String chatApiKey() { return chatApiKey; }
    public String visionModel() { return visionModel; }

    /** 生效 provider：运行时覆盖 > yml。 */
    public String effectiveChatProvider(String ymlProvider) {
        return chatProvider != null ? chatProvider : ymlProvider;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
