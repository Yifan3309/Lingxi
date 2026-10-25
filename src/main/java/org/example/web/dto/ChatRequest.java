package org.example.web.dto;

/**
 * M1：sessionId 对应窗口记忆（内存态，默认 "default"）；question 为当前问题。
 */
public record ChatRequest(String sessionId, String question) {
}
