package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Map;

/**
 * AI 模型接入配置（M0）。
 *
 * chat 与 embedding 各自独立选择 provider，修改
 * app.ai.chat.provider / app.ai.embedding.provider 即可在
 * openai-compatible（智谱 GLM、DeepSeek 等 OpenAI 兼容端点）
 * 与 ollama（本地）之间切换，无需改代码。
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(Chat chat, @DefaultValue Embedding embedding, @DefaultValue Vision vision,
                           @DefaultValue Extract extract) {

    public record Chat(
            @DefaultValue("openai-compatible") String provider,
            @DefaultValue OpenAiCompatible openaiCompatible,
            @DefaultValue Ollama ollama) {
    }

    public record Embedding(
            @DefaultValue("openai-compatible") String provider,
            @DefaultValue OpenAiCompatible openaiCompatible,
            @DefaultValue Ollama ollama,
            @DefaultValue Onnx onnx) {
    }

    public record OpenAiCompatible(
            @DefaultValue("https://api.openai.com/v1") String baseUrl,
            @DefaultValue String apiKey,
            @DefaultValue("gpt-4o-mini") String chatModel,
            @DefaultValue("text-embedding-3-small") String embeddingModel,
            @DefaultValue("0.7") Double temperature,
            @DefaultValue("60s") Duration timeout) {
    }

    public record Ollama(
            @DefaultValue("http://localhost:11434") String baseUrl,
            @DefaultValue("qwen3:8b") String chatModel,
            @DefaultValue("nomic-embed-text") String embeddingModel,
            @DefaultValue("0.7") Double temperature,
            @DefaultValue("120s") Duration timeout) {
    }

    /** 本地 ONNX 嵌入模型（如 Multilingual-E5-small），无需网络与密钥。 */
    public record Onnx(
            @DefaultValue("models/embedding/Multilingual-E5-small/model.onnx") String modelPath,
            @DefaultValue("models/embedding/Multilingual-E5-small/tokenizer.json") String tokenizerPath,
            @DefaultValue("MEAN") String poolingMode) {
    }

    /**
     * 视觉模型（图片/扫描 PDF 文字提取），走 OpenAI 兼容端点。
     * base-url/api-key 为 null = 继承 chat 的 openai-compatible 配置。
     */
    public record Vision(
            @DefaultValue("glm-4v-flash") String model,
            @Nullable String baseUrl,
            @Nullable String apiKey,
            @DefaultValue("120s") Duration timeout) {
    }

    /**
     * 概念抽取 / 主题分组（后台批处理）的额外请求参数。
     *
     * <p><b>存在的理由：让思考不占输出预算。</b>OpenAI 兼容端点的 {@code max_tokens} 是
     * 生成总预算，reasoning token 与正文共用它。抽取只给 512，思考模型会把预算全用在推理上、
     * 正文返回空字符串——而空输出会被当成"这篇确实没概念"，静默清掉已有概念。
     *
     * <p>{@code returnThinking(false)} 解决不了这个问题：它只是不把思考递给我们，
     * 思考照样生成、照样计费。必须在<b>请求侧</b>让模型别思考。
     * Ollama 有原生的 {@code think(false)}；OpenAI 协议没有这个字段，各家的写法还不一样，
     * 所以这里开放一个原样并入请求体的参数表：
     *
     * <pre>
     * # 智谱 GLM-4.5+ / mimo（2026-11 实测有效）
     * app.ai.extract.disable-thinking.thinking.type: disabled
     * # 通义千问 / vLLM
     * app.ai.extract.disable-thinking.enable_thinking: false
     * </pre>
     *
     * <p><b>只作用于抽取模型，对话模型不碰这些参数</b>（对话的思考照旧显示折叠框）。
     * 端点不认这个字段会返回 400——抽取是后台任务，只记 WARN 不影响保存，
     * 那种端点把这一段改成 {@code {}} 即可。
     */
    public record Extract(
            @Nullable Map<String, Object> disableThinking) {
    }
}
