package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 笔记 AI 富化（自动标签建议 + 摘要）配置。失败不阻塞保存，仅记日志。
 */
@ConfigurationProperties(prefix = "app.enrich")
public record EnrichProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("3000") int maxContentChars) {
}
