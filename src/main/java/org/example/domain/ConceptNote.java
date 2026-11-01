package org.example.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * 笔记 → 概念的提及（概念层与文档层之间的跨层边）。
 *
 * <p>这条边是**事实**：这篇笔记里确实出现了这个概念，而且 {@code description}
 * 是原文里作者自己写的那句话，点回笔记就能看到。所以它可以被用户验证，
 * 而"概念 A 依赖概念 B"这种推导不落库（见 doc/08 §5.1 的取舍）。
 *
 * <p>{@code (concept_id, note_id)} 唯一：一篇笔记对一个概念只留一条记录，
 * 因为位置信息不存（读时用 {@code indexOf} 现算），同一篇里的多次出现没有额外信息。
 *
 * <p>重抽时按 note_id 整体删除再插入，不做增量追加——追加会把旧名字的残影留在库里，
 * 而概念忽隐忽现比少一个概念更伤信任。
 */
@Entity
@Table(name = "concept_note",
        uniqueConstraints = @UniqueConstraint(name = "uk_concept_note", columnNames = {"concept_id", "note_id"}))
public class ConceptNote {

    @Id
    private String id;

    @Column(name = "concept_id", nullable = false)
    private String conceptId;

    @Column(name = "note_id", nullable = false)
    private String noteId;

    /** 这篇笔记里对这个概念的说明（原文摘录），可能为空：只有名字没有解释的情况很常见 */
    @Column(length = 2048)
    private String description;

    /** explicit = 标题/双链明示，implicit = 正文顺带提到。语义落库，位置永远派生 */
    @Column(nullable = false, length = 16)
    private String kind = "implicit";

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getConceptId() { return conceptId; }
    public void setConceptId(String conceptId) { this.conceptId = conceptId; }
    public String getNoteId() { return noteId; }
    public void setNoteId(String noteId) { this.noteId = noteId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
}
