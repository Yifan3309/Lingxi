package org.example.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * 主题（概念层的上一层），每条概念最终都要有一个归属。
 *
 * <p>{@code source} 记录主题是怎么来的，这比主题本身更重要——
 * 实测语义聚类出的跨篇主题 46% 是错的（doc/08 §6.5），所以"这个主题凭什么成立"
 * 必须可追溯。v1 只启用 folder（结构证据 E2）：文件夹是人分的，语义上不会错，
 * 只是可能很稀疏。语义聚类等有判别器之后再加，加的时候只是多一种 source。
 *
 * <p>{@code structuralNull} 标记兜底桶「未分类」：它存在是为了让每条概念都有归属、
 * 导航时不出现悬空节点，但**图算法必须把它当 NULL 跳过**——它挂着大量概念，
 * 一旦参与传播就会成为全图最大 hub，把 PageRank 权重全吸走。
 */
@Entity
@Table(name = "topic", uniqueConstraints = @UniqueConstraint(name = "uk_topic_name", columnNames = {"name", "source"}))
public class Topic {

    @Id
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    /** folder = 文件夹推导｜clustered = 语义聚类｜manual = 人工确认 */
    @Column(nullable = false, length = 16)
    private String source;

    @Column(name = "structural_null", nullable = false)
    private boolean structuralNull;

    /** 成员概念数（派生），用于排序与展示 */
    @Column(name = "concept_count", nullable = false)
    private int conceptCount;

    /**
     * 生成这批主题的规则版本。
     *
     * <p>主题层是纯派生数据，规则一改（比如文件夹从完整路径折到顶层），
     * 旧的主题行不会自己变。需要一个能对比的标记：重算时写入当前版本，
     * 读取前发现库里的版本不是当前值，就知道这批主题是旧规则留下的，该重算。
     */
    // 可空：ddl-auto=update 给已有行加 NOT NULL 列在 H2 上会失败，
    // 而旧行本来就该被当作"旧规则留下的"，null 即版本未知
    @Column(name = "rule_version")
    private Integer ruleVersion;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public boolean isStructuralNull() { return structuralNull; }
    public void setStructuralNull(boolean structuralNull) { this.structuralNull = structuralNull; }
    public int getConceptCount() { return conceptCount; }
    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }
    public void setConceptCount(int conceptCount) { this.conceptCount = conceptCount; }
}
