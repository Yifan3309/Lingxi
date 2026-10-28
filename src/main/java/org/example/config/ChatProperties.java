package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 问答附件（@链接）配置：数量/字数上限、本地文件白名单与安全开关、上传缓存。
 * 附件仅本轮上下文（不入库）；本地文件只读且默认仅放行 loopback 请求。
 */
@ConfigurationProperties(prefix = "app.chat")
public record ChatProperties(
        @DefaultValue("8") int maxAttachments,
        @DefaultValue("30000") int maxTotalChars,
        @DefaultValue("8000") int maxNoteChars,
        @DefaultValue("12000") int maxFileChars,
        @DefaultValue("20000") int maxFolderChars,
        @DefaultValue("10") int maxFolderNotes,
        @DefaultValue("3000") int maxFolderNoteChars,
        @DefaultValue("20971520") long maxFileBytes,
        @DefaultValue("100") int minPdfChars,
        @DefaultValue("true") boolean allowLocalFiles,
        @DefaultValue("true") boolean localFilesRequireLoopback,
        @DefaultValue("60") int uploadTtlMinutes,
        @DefaultValue("32") int uploadCacheSize,
        @DefaultValue({"md", "markdown", "txt", "pdf", "docx", "doc", "pptx", "ppt",
                "html", "htm", "json", "yaml", "yml", "xml", "csv",
                "java", "py", "js", "ts", "sql", "sh", "go", "rs", "c", "cpp", "h", "cs"})
        List<String> allowedExtensions) {
}
