package org.example.web.dto;

/**
 * 分块布局：index 与 RAG 来源里的 chunk_index 对应，
 * [charStart, charEnd) 为该块正文在当前 content 中的原文区间。
 * 读时由 NoteChunker 重算（不落库），始终与笔记当前正文同步。
 */
public record ChunkLayout(int index, int charStart, int charEnd, String headingPath) {
}
