package org.example.web.dto;

import java.util.List;

/**
 * sessionId 对应窗口记忆（内存态，默认 "default"）；question 为当前问题。
 * attachments 可选：@ 链接的本轮上下文附件（笔记/文件夹/本地文件/上传），
 * 历史只存 question，附件不落库。
 */
public record ChatRequest(String sessionId, String question, List<ChatAttachment> attachments) {
    public ChatRequest(String sessionId, String question) {
        this(sessionId, question, null);
    }
}
