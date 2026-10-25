package org.example.web.dto;

/**
 * 回答引用的来源：对应一条笔记分块，前端可据此跳转原笔记。
 */
public record SourceRef(String noteId, String title, Integer chunkIndex, Double score, String excerpt) {
}
