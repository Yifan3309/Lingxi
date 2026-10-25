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
 * 笔记分块的元数据行。向量本体存向量库（embeddings.json），经 chunk_id 关联；
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
    public String getNoteId() { return noteId; }
    public void setNoteId(String noteId) { this.noteId = noteId; }
    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public Instant getCreatedAt() { return createdAt; }
}
