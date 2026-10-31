package org.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时模型覆盖（前端可改）：按 provider 分槽记忆，独立于 application.yml 的生效层。
 *
 * 每个 provider 一份槽（model/baseUrl/apiKey），切换 provider = 只换激活槽，旧槽原样保留——
 * 云端与本地 Ollama 的配置互不覆盖，来回切不用重填。槽字段为 null = 该项回退 yml。
 * 覆盖持久化到 data/runtime-config.json（gitignored、原子写），重启后仍生效。
 */
@Service
public class RuntimeModelConfig {

    private static final Logger log = LoggerFactory.getLogger(RuntimeModelConfig.class);
    private static final Path FILE = Path.of("data/runtime-config.json").toAbsolutePath().normalize();

    /** 对话 provider 覆盖（openai-compatible | ollama；null = 跟随 yml） */
    private volatile String chatProvider;
    /** 视觉模型名 */
    private volatile String visionModel;
    /** 每 provider 一份覆盖槽：model / baseUrl / apiKey（值 null = 回退 yml） */
    private final Map<String, Map<String, String>> profiles = new ConcurrentHashMap<>();

    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    void load() {
        if (!Files.exists(FILE)) {
            return;
        }
        try {
            Map<String, Object> json = mapper.readValue(FILE.toFile(), Map.class);
            chatProvider = str(json.get("chatProvider"));
            visionModel = str(json.get("visionModel"));
            Object rawProfiles = json.get("profiles");
            if (rawProfiles instanceof Map<?, ?> pm) {
                for (Map.Entry<?, ?> e : pm.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> slot) {
                        Map<String, String> copy = new HashMap<>();
                        slot.forEach((k, v) -> copy.put(String.valueOf(k), str(v)));
                        profiles.put(String.valueOf(e.getKey()), copy);
                    }
                }
            } else {
                // 旧扁平格式迁移：chatModel/chatBaseUrl/chatApiKey 归入其所属 provider 的槽
                Map<String, String> legacy = new HashMap<>();
                String legacyModel = str(json.get("chatModel"));
                String legacyBaseUrl = str(json.get("chatBaseUrl"));
                String legacyApiKey = str(json.get("chatApiKey"));
                if (legacyModel != null) {
                    legacy.put("model", legacyModel);
                }
                if (legacyBaseUrl != null) {
                    legacy.put("baseUrl", legacyBaseUrl);
                }
                if (legacyApiKey != null) {
                    legacy.put("apiKey", legacyApiKey);
                }
                if (!legacy.isEmpty()) {
                    String owner = chatProvider != null ? chatProvider : "openai-compatible";
                    profiles.put(owner, legacy);
                    log.info("已迁移旧版运行时覆盖到 {} 的槽", owner);
                }
            }
            log.info("已加载运行时模型覆盖：chatProvider={}, visionModel={}", chatProvider, visionModel);
        } catch (IOException e) {
            log.warn("运行时模型覆盖读取失败（忽略，使用 yml 配置）：{}", String.valueOf(e.getMessage()));
        }
    }

    /**
     * 保存：写入 provider 自己的槽并设为激活 provider，其余 provider 的槽原样保留。
     * model/baseUrl：null = 保持槽内不变，"" = 清除（回退 yml），非空 = 覆盖；
     * apiKey/visionModel：null 或空白 = 保持（密钥脱敏不回填），非空 = 覆盖。
     */
    public synchronized void save(String provider, String model, String baseUrl, String apiKey, String visionModel) {
        String p = blankToNull(provider) != null ? blankToNull(provider) : activeProvider();
        Map<String, String> slot = new HashMap<>(profiles.getOrDefault(p, Map.of()));
        if (model != null) {
            slot.put("model", blankToNull(model));
        }
        if (baseUrl != null) {
            slot.put("baseUrl", blankToNull(baseUrl));
        }
        if (apiKey != null) {
            String key = blankToNull(apiKey);
            if (key != null) {
                slot.put("apiKey", key); // 空白密钥 = 保持（脱敏输入框留空即不改）
            }
        }
        profiles.put(p, slot);
        if (blankToNull(provider) != null) {
            this.chatProvider = blankToNull(provider);
        }
        if (blankToNull(visionModel) != null) {
            this.visionModel = blankToNull(visionModel);
        }
        persist();
    }

    /** 只改内存生效值（不落盘）：包内可见，供单测构造覆盖状态。参数语义同 save。 */
    synchronized void apply(String provider, String model, String baseUrl, String apiKey, String visionModel) {
        String p = blankToNull(provider) != null ? blankToNull(provider) : activeProvider();
        Map<String, String> slot = new HashMap<>(profiles.getOrDefault(p, Map.of()));
        if (model != null) {
            slot.put("model", blankToNull(model));
        }
        if (baseUrl != null) {
            slot.put("baseUrl", blankToNull(baseUrl));
        }
        if (apiKey != null) {
            String key = blankToNull(apiKey);
            if (key != null) {
                slot.put("apiKey", key);
            }
        }
        profiles.put(p, slot);
        if (blankToNull(provider) != null) {
            this.chatProvider = blankToNull(provider);
        }
        if (blankToNull(visionModel) != null) {
            this.visionModel = blankToNull(visionModel);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(FILE.getParent());
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("chatProvider", this.chatProvider);
            json.put("profiles", profiles);
            json.put("visionModel", this.visionModel);
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), json);
            try {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("运行时模型覆盖写入失败（内存中仍生效）：{}", String.valueOf(e.getMessage()));
        }
    }

    /** 激活 provider 的槽（无 yml 上下文时的默认视图：override 优先，否则 openai-compatible）。 */
    private String activeProvider() {
        return chatProvider != null ? chatProvider : "openai-compatible";
    }

    /** 指定 provider 的覆盖槽（只读视图，字段可能为 null = 回退 yml）。 */
    public Map<String, String> slot(String provider) {
        return profiles.getOrDefault(provider, Map.of());
    }

    /** 激活 provider 的槽（provider 覆盖优先，否则 openai-compatible；仅供测试与展示层用）。 */
    public Map<String, String> activeSlot() {
        return slot(activeProvider());
    }

    public String chatProvider() {
        return chatProvider;
    }

    public String visionModel() {
        return visionModel;
    }

    /** 生效 provider：运行时覆盖 > yml。 */
    public String effectiveChatProvider(String ymlProvider) {
        return chatProvider != null ? chatProvider : ymlProvider;
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
