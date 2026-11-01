package org.example.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * 概念 → 主题的 part_of 边。
 *
 * <p>主键是 (conceptId, topicId)，**不唯一于 concept**：一个概念可以同时属于多个主题。
 * 概念天然跨主题（`页面置换` 既属于内存管理也属于页面调度），强制唯一会制造伪选择；
 * 而且多归属本身让概念成为主题之间的桥，是目前唯一有语义依据的跨主题连接。
 *
 * <p>{@code weight} 是归属强度，{@code primary} 标记唯一主归属。
 * 实测本语料 second/top 的比例最高只有 0.67、没有自然拐点，所以多归并是稀疏的——
 * 表结构支持 N 条，绝大多数概念只有一条，这是正常的，不是设计没生效。
 */
@Entity
@Table(name = "concept_topic")
@IdClass(ConceptTopic.Key.class)
public class ConceptTopic {

    public static class Key implements Serializable {
        private String conceptId;
        private String topicId;

        public Key() {
        }

        public Key(String conceptId, String topicId) {
            this.conceptId = conceptId;
            this.topicId = topicId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return Objects.equals(conceptId, other.conceptId) && Objects.equals(topicId, other.topicId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(conceptId, topicId);
        }
    }

    @Id
    @Column(name = "concept_id", nullable = false)
    private String conceptId;

    @Id
    @Column(name = "topic_id", nullable = false)
    private String topicId;

    @Column(nullable = false)
    private double weight;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    public String getConceptId() { return conceptId; }
    public void setConceptId(String conceptId) { this.conceptId = conceptId; }
    public String getTopicId() { return topicId; }
    public void setTopicId(String topicId) { this.topicId = topicId; }
    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }
    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }
}
