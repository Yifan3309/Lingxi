package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 网页剪藏配置：URL 抓取 → 图片落地 → LLM 清洗重写 → 笔记 source=clip。
 */
@ConfigurationProperties(prefix = "app.clip")
public record ClipProperties(
        @DefaultValue("5") int connectTimeoutSeconds,
        @DefaultValue("20") int readTimeoutSeconds,
        @DefaultValue("5242880") long maxBytes,
        @DefaultValue("Mozilla/5.0 (compatible; lingxi-clip/1.0)") String userAgent,
        @DefaultValue("10") int maxImages,
        @DefaultValue("3145728") long maxImageBytes,
        /** 超过此字符数的正文跳过 LLM 重写（避免半篇清洗），保留 Tika 原文 */
        @DefaultValue("15000") int rewriteMaxChars) {
}
