package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.domain.ChatMessage;
import org.example.domain.ChatSession;
import org.example.repository.ChatMessageRepository;
import org.example.repository.ChatSessionRepository;
import org.example.web.dto.SourceRef;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 问答历史落库（M2）。用户消息即时保存；助手消息在流式完成后保存
 * （含来源 JSON、token 用量与延迟）。
 */
@Service
public class ChatHistoryService {

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatHistoryService(ChatSessionRepository sessionRepo, ChatMessageRepository messageRepo) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
    }

    public void ensureSession(String sessionId) {
        if (!sessionRepo.existsById(sessionId)) {
            ChatSession session = new ChatSession();
            session.setId(sessionId);
            sessionRepo.save(session);
        }
    }

    public void saveUserMessage(String sessionId, String question) {
        ensureSession(sessionId);
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole("user");
        message.setContent(question);
        messageRepo.save(message);
    }

    public void saveAssistantMessage(String sessionId, String answer, List<SourceRef> sources,
                                     Integer tokensIn, Integer tokensOut, Long latencyMs) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole("assistant");
        message.setContent(answer);
        message.setRefs(toRefsJson(sources));
        message.setTokensIn(tokensIn);
        message.setTokensOut(tokensOut);
        message.setLatencyMs(latencyMs);
        messageRepo.save(message);
    }

    public List<ChatMessage> messages(String sessionId) {
        return messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    private String toRefsJson(List<SourceRef> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
