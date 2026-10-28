package org.example.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 旧仓库迁移凭证（幂等）：记录每个源 md 文件对应的笔记与内容哈希。
 * 重导时哈希未变且笔记仍在 → 跳过；哈希变更 → 原地更新（保留笔记 id，自动重嵌入）；
 * 笔记已被用户删除 → 重新创建。删除源文件不影响已导入笔记（不做联动删除）。
 */
@Entity
@Table(name = "note_import")
public class NoteImport {

    @Id
    private String id;

    @Column(name = "note_id", nullable = false)
    private String noteId;

    /** 源文件绝对路径（规范化），唯一 —— 幂等查重键 */
    @Column(name = "source_path", nullable = false, unique = true, length = 1024)
    private String sourcePath;

    /** 源文件内容 SHA-256（hex 64 位） */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getId() {
        return id;
    }

    public String getNoteId() {
        return noteId;
    }

    public void setNoteId(String noteId) {
        this.noteId = noteId;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
