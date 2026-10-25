package org.example.web.dto;

/**
 * 语义相关笔记推荐项（笔记级，非分块级）。
 */
public record RelatedNote(String noteId, String title, Double score) {
}
