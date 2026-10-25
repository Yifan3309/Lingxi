package org.example.web.dto;

/**
 * title 省略时取 content 首行（≤30 字符）。
 * folder：null=不修改（PUT 语义），空串=移出文件夹，非空=归属该文件夹。
 */
public record NoteRequest(String title, String content, String tags, String folder) {
}
