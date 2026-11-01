package org.example.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * 概念（概念层的节点）。
 *
 * <p>身份由 {@code norm_key} 唯一约束保证——这是整个概念层的地基：
 * 跨篇复用、时间序列、同主题聚合全都建立在"同一个概念只有一个 id"之上。
 * 一旦同一件事在库里有两个 id，后面所有统计都会被稀释，且无法事后修复。
 *
 * <p>刻意不存 {@code description}：说明是"每篇笔记各自贡献一段"（见 {@link ConceptNote}），
 * 合并成一段是派生结果，换合并策略就要重算。存了就得维护一致性，而这里的一致性最容易坏。
 */
@Entity
@Table(name = "concept", uniqueConstraints = @UniqueConstraint(name = "uk_concept_key", columnNames = "norm_key"))
public class Concept {

    @Id
    private String id;

    /** 首次出现时的写法，后续同 key 的概念沿用这个显示名 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 归一化键：小写、去空白与标点。合并与去重只看它 */
    @Column(name = "norm_key", nullable = false, length = 128)
    private String normKey;

    /** 覆盖的笔记数（派生，抽取后重算），用于排序与"是否形成结构"的判断 */
    @Column(name = "note_count", nullable = false)
    private int noteCount;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (firstSeenAt == null) {
            firstSeenAt = now;
        }
        lastSeenAt = now;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNormKey() { return normKey; }
    public void setNormKey(String normKey) { this.normKey = normKey; }
    public int getNoteCount() { return noteCount; }
    public void setNoteCount(int noteCount) { this.noteCount = noteCount; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
