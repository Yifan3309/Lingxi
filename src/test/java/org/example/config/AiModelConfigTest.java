package org.example.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型生效配置解析（单一真相源）的回归矩阵。
 * 背景：runtime 覆盖与 provider 语义不匹配曾致 chat 全量 404（/v1 串进 OllamaChatModel）、
 * vision 静默失效（chat 地址被带给 vision）——启动装配、热切换、配置展示三处曾各自实现，
 * 现在只允许走 resolveChat / resolveVision，这里锁死它们的裁决规则。
 */
@DisplayName("AiModelConfig：runtime 覆盖与 provider 的裁决矩阵")
class AiModelConfigTest {

    private static final String OL_BASE = "http://localhost:11434";
    private static final String OC_BASE = "https://open.bigmodel.cn/api/paas/v4";

    private static AiProperties props() {
        return new AiProperties(
                new AiProperties.Chat("openai-compatible",
                        new AiProperties.OpenAiCompatible(OC_BASE, "oc-key", "oc-chat", null, 0.7, java.time.Duration.ofSeconds(60)),
                        new AiProperties.Ollama(OL_BASE, "ol-chat", null, 0.7, java.time.Duration.ofSeconds(120))),
                new AiProperties.Embedding("onnx", null, null,
                        new AiProperties.Onnx("m", "t", "MEAN")),
                new AiProperties.Vision("glm-4v-flash", "https://vision.example/v1", "vision-key", java.time.Duration.ofSeconds(120)));
    }

    private static RuntimeModelConfig runtime(String provider, String model, String baseUrl, String apiKey, String visionModel) {
        RuntimeModelConfig r = new RuntimeModelConfig();
        r.apply(provider, model, baseUrl, apiKey, visionModel);
        return r;
    }

    @Test
    @DisplayName("chat/ollama：openai 风格 /v1 运行时地址被丢弃，退回 yml 的 ollama 地址")
    void ollamaDropsOpenAiStyleRuntimeBase() {
        AiModelConfig.EffectiveChat c = AiModelConfig.resolveChat(props(),
                runtime("ollama", "m", "http://127.0.0.1:11434/v1", "stale-key", null));

        assertThat(c.baseUrl()).isEqualTo(OL_BASE);
        assertThat(c.apiKey()).isNull(); // ollama 无密钥概念
        assertThat(c.model()).isEqualTo("m");
    }

    @Test
    @DisplayName("chat/ollama：原生形态的运行时地址是有意覆盖，保留")
    void ollamaKeepsNativeRuntimeBase() {
        AiModelConfig.EffectiveChat c = AiModelConfig.resolveChat(props(),
                runtime("ollama", null, "http://192.168.1.5:11434", null, null));

        assertThat(c.baseUrl()).isEqualTo("http://192.168.1.5:11434");
        assertThat(c.model()).isEqualTo("ol-chat"); // 模型未覆盖 → yml
    }

    @Test
    @DisplayName("chat/ollama：provider 覆盖为 ollama 而地址未覆盖时，走 yml ollama 段")
    void ollamaFallsBackToYml() {
        AiModelConfig.EffectiveChat c = AiModelConfig.resolveChat(props(), runtime("ollama", null, null, null, null));

        assertThat(c.baseUrl()).isEqualTo(OL_BASE);
        assertThat(c.model()).isEqualTo("ol-chat");
        assertThat(c.apiKey()).isNull();
    }

    @Test
    @DisplayName("chat/openai：运行时覆盖优先于 yml")
    void openAiPrefersRuntimeOverrides() {
        AiModelConfig.EffectiveChat c = AiModelConfig.resolveChat(props(),
                runtime("openai-compatible", "rt-model", "https://api.deepseek.com/v1", "rt-key", null));

        assertThat(c.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(c.apiKey()).isEqualTo("rt-key");
        assertThat(c.model()).isEqualTo("rt-model");
    }

    @Test
    @DisplayName("chat/openai：provider 跟随 yml（runtime.provider 为 null）时走 oc 段")
    void openAiFallsBackToYml() {
        AiModelConfig.EffectiveChat c = AiModelConfig.resolveChat(props(), runtime(null, null, null, null, null));

        assertThat(c.baseUrl()).isEqualTo(OC_BASE);
        assertThat(c.model()).isEqualTo("oc-chat");
        assertThat(c.apiKey()).isEqualTo("oc-key");
    }

    @Test
    @DisplayName("vision：chat 在 ollama 时，chat 的运行时地址/密钥不得带给 vision")
    void visionNotHijackedByOllamaChatRuntime() {
        AiModelConfig.EffectiveVision v = AiModelConfig.resolveVision(props(),
                runtime("ollama", null, "http://127.0.0.1:11434/v1", "stale-key", "rt-vision"));

        assertThat(v.baseUrl()).isEqualTo("https://vision.example/v1"); // yml vision 段
        assertThat(v.apiKey()).isEqualTo("vision-key");
        assertThat(v.model()).isEqualTo("rt-vision"); // vision 自己的覆盖照常生效
    }

    @Test
    @DisplayName("vision：chat 在 openai 时，vision 继承 chat 的运行时地址/密钥（既有设计）")
    void visionInheritsOpenAiChatRuntime() {
        AiModelConfig.EffectiveVision v = AiModelConfig.resolveVision(props(),
                runtime("openai-compatible", null, "https://api.deepseek.com/v1", "rt-key", null));

        assertThat(v.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(v.apiKey()).isEqualTo("rt-key");
        assertThat(v.model()).isEqualTo("glm-4v-flash");
    }

    @Test
    @DisplayName("vision：vision 段未配地址时回退 chat 的 yml oc 段")
    void visionFallsBackToYmlChatBase() {
        AiProperties p = new AiProperties(
                props().chat(), props().embedding(),
                new AiProperties.Vision("glm-4v-flash", null, null, java.time.Duration.ofSeconds(120)));

        AiModelConfig.EffectiveVision v = AiModelConfig.resolveVision(p, runtime(null, null, null, null, null));

        assertThat(v.baseUrl()).isEqualTo(OC_BASE);
        assertThat(v.apiKey()).isEqualTo("oc-key");
    }

    @Test
    @DisplayName("chat 覆盖值首尾空白在 apply 时裁掉，纯空白等同未覆盖")
    void applyTrimsAndBlanks() {
        RuntimeModelConfig r = runtime(" ollama ", "  ", null, "", null);

        assertThat(r.chatProvider()).isEqualTo("ollama");
        assertThat(r.activeSlot().get("model")).isNull();
        assertThat(r.activeSlot().get("baseUrl")).isNull();
        assertThat(r.effectiveChatProvider("openai-compatible")).isEqualTo("ollama");
        assertThat(r.effectiveChatProvider(null)).isEqualTo("ollama");
    }

    @Test
    @DisplayName("切换 provider 不覆盖对方槽位：ollama 保存后云端槽原样保留，来回切不丢配置")
    void providerSwitchKeepsBothSlots() {
        RuntimeModelConfig r = runtime("openai-compatible", "deepseek-chat", "https://api.deepseek.com/v1", "rt-key", null);
        r.apply("ollama", "qwen_3.5_2BQ4:latest", "http://localhost:11434", null, null);

        assertThat(r.chatProvider()).isEqualTo("ollama");
        assertThat(r.slot("openai-compatible").get("model")).isEqualTo("deepseek-chat");
        assertThat(r.slot("openai-compatible").get("baseUrl")).isEqualTo("https://api.deepseek.com/v1");
        assertThat(r.slot("ollama").get("model")).isEqualTo("qwen_3.5_2BQ4:latest");
        // 切回云端：云端槽完整恢复
        r.apply("openai-compatible", null, null, null, null);
        AiModelConfig.EffectiveChat c = AiModelConfig.resolveChat(props(), r);
        assertThat(c.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(c.model()).isEqualTo("deepseek-chat");
    }
}
