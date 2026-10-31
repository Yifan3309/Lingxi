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
 * 笔记分块：正文与向量本体同库同事务（embedding BLOB），行主键即向量库条目 id；
 * 记录 embedding_model 供更换模型后判断哪些块需要重嵌入（风险 R1）。
 */
@Entity
@Table(name = "note_chunk", uniqueConstraints = @UniqueConstraint(name = "uk_note_chunk", columnNames = {"note_id", "chunk_index"}))
public class NoteChunk {

    @Id
    private String id;

    @Column(name = "note_id", nullable = false)
    private String noteId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    // 同 Note.content：VARCHAR 避免 CLOB 函数校验问题
    @Column(length = 65536)
    private String text;

    /** 向量本体：维度×4 字节原始浮点（二进制直存，无人类可读开销）；4096 容纳 1024 维模型 */
    @Column(name = "embedding", length = 4096)
    private byte[] embedding;

    @Column(name = "embedding_model", nullable = false)
    private String embeddingModel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getNoteId() { return noteId; }
    public void setNoteId(String noteId) { this.noteId = noteId; }
    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public byte[] getEmbedding() { return embedding; }
    public void setEmbedding(byte[] embedding) { this.embedding = embedding; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public Instant getCreatedAt() { return createdAt; }
}
