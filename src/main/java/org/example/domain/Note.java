package org.example.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.UUID;

/**
 * 笔记主表。content 为 Markdown 纯文本；tags 为逗号分隔的标签串（M2 换关联表）。
 */
@Entity
@Table(name = "note")
public class Note {

    @Id
    private String id;

    @Column(nullable = false)
    private String title;

    // 不用 @Lob：CLOB 上 IgnoreCase 会生成 upper() 导致 Hibernate 校验失败；VARCHAR 上 LIKE/UPPER 均正常
    @Column(length = 65536)
    private String content;

    private String tags;

    /** 所属文件夹（层级分组用），空/null = 未归类。可空：含旧数据表加 NOT NULL 列会被 H2 拒绝 */
    @Column(length = 128)
    private String folder;

    /** 来源预留：manual / ocr / clip / voice（M3 多模态输入用）。可空+DB 默认值：对含旧数据的表加 NOT NULL 列会被 H2 拒绝 */
    @ColumnDefault("'manual'")
    @Column(name = "source")
    private String source = "manual";

    /** AI 建议标签（逗号分隔），仅建议不自动应用，用户经 PUT 采纳 */
    @Column(name = "suggested_tags")
    private String suggestedTags;

    /** AI 生成摘要，异步填充 */
    @Column(length = 4096)
    private String summary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSuggestedTags() { return suggestedTags; }
    public void setSuggestedTags(String suggestedTags) { this.suggestedTags = suggestedTags; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
