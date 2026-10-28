package org.example.web.dto;

import java.time.Instant;

/**
 * 列表/选择器用的轻量投影：**不含 content**。
 * content 是 65k 的列，列表每刷新一次就全量搬运，1k 篇时单次响应可达数 MB；
 * 正文只在 GET /api/notes/{id} 返回，编辑与预览都走详情接口。
 * 字段与前端列表（标题/文件夹/标签/相对时间）一一对应，不多带。
 */
public record NoteSummary(String id, String title, String tags, String folder, String source, Instant updatedAt) {
}
