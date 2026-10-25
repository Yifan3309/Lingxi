package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.lang.Nullable;

import java.time.Duration;

/**
 * AI 模型接入配置（M0）。
 *
 * chat 与 embedding 各自独立选择 provider，修改
 * app.ai.chat.provider / app.ai.embedding.provider 即可在
 * openai-compatible（智谱 GLM、DeepSeek 等 OpenAI 兼容端点）
 * 与 ollama（本地）之间切换，无需改代码。
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(Chat chat, @DefaultValue Embedding embedding, @DefaultValue Vision vision) {

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
}
