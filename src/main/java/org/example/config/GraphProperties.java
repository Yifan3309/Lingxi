package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 知识图谱配置（M3）。
 * semanticMinScore 注意：E5 等模型中文短文本的余弦基线偏高（实测不相关对 ~0.84-0.86、
 * 相关对 ~0.95，"天花板效应"），0.90 才能切干净；笔记普遍同域时可能一条边不出，可调低。
 */
@ConfigurationProperties(prefix = "app.graph")
public record GraphProperties(
        @DefaultValue("0.90") double semanticMinScore,
        @DefaultValue("2") int semanticTopK) {
}
