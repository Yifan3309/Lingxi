package org.example.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 多模态导入资产（M4 落地）：原文件存 data/assets/，本表记关联与解析结果。
 * kind: image（纯图片，视觉提取）/ pdf_scan（扫描版 PDF）/ file（结构化文档，Tika 抽取）。
 */
@Entity
@Table(name = "note_asset")
public class NoteAsset {

    @Id
    private String id;

    @Column(name = "note_id", nullable = false)
    private String noteId;

    @Column(nullable = false)
    private String kind;

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    private String mime;

    /** 视觉模型/解析器提取的文本（图片类）；列可空原则 */
    @Column(length = 65536)
    private String extractedText;

    /** pending / done / failed */
    @Column(nullable = false)
    private String status;

    @Column(length = 1024)
    private String error;

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
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getMime() { return mime; }
    public void setMime(String mime) { this.mime = mime; }
    public String getExtractedText() { return extractedText; }
    public void setExtractedText(String extractedText) { this.extractedText = extractedText; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
}
