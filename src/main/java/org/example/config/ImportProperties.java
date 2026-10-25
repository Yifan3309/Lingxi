package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 多模态导入配置（分级解析：Tika 一级 + 视觉 LLM 二级）。
 */
@ConfigurationProperties(prefix = "app.import")
public record ImportProperties(
        @DefaultValue("20971520") long maxBytes,
        @DefaultValue("100") int minPdfChars,
        @DefaultValue("10") int maxPdfPages) {
}
