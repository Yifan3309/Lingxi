package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 旧仓库一键迁移参数。隐藏目录（. 开头）一律排除，excludeDirs 补充非隐藏的排除项。
 */
@ConfigurationProperties(prefix = "app.vault")
public record VaultProperties(
        @DefaultValue("5000") int maxFiles,
        @DefaultValue("10485760") long maxMdBytes,
        @DefaultValue("10485760") long maxImageBytes,
        @DefaultValue({".trash", "node_modules", "__pycache__", ".stfolder"}) List<String> excludeDirs) {
}
