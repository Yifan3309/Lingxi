package org.example.web.dto;

/**
 * title 省略时取 content 首行（≤30 字符）自动生成。
 */
public record NoteRequest(String title, String content, String tags) {
}
