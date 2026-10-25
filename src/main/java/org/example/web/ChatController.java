package org.example.web;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import org.example.domain.ChatMessage;
import org.example.rag.NoteAssistant;
import org.example.service.ChatHistoryService;
import org.example.web.dto.ChatDoneEvent;
import org.example.web.dto.ChatRequest;
import org.example.web.dto.SourceRef;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RAG 问答：基于全部笔记检索后回答，SSE 流式返回；会话与消息落库（M2）。
 * 事件约定：message = 增量 token；done = 结束帧（finishReason + 来源列表 sources）；
 * error = 失败帧。sessionId 为空时用 "default"，多轮上下文按会话隔离（内存态，重启即清；
 * 历史消息在库中可回看）。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final NoteAssistant noteAssistant;
    private final ChatHistoryService historyService;

    public ChatController(NoteAssistant noteAssistant, ChatHistoryService historyService) {
        this.noteAssistant = noteAssistant;
        this.historyService = historyService;
    }

    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody ChatRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? "default"
                : request.sessionId();
        SseEmitter emitter = new SseEmitter(180_000L);
        List<SourceRef> sources = new CopyOnWriteArrayList<>();
        StringBuilder answer = new StringBuilder();
        long startNanos = System.nanoTime();

        historyService.saveUserMessage(sessionId, request.question());

        TokenStream stream = noteAssistant.chat(sessionId, request.question());
        stream
                .onPartialResponse(token -> {
                    answer.append(token);
                    send(emitter, "message", token);
                })
                .onRetrieved(contents -> contents.forEach(c -> sources.add(SourceRef.from(c))))
                .onCompleteResponse(response -> {
                    String finishReason = response.metadata() == null || response.metadata().finishReason() == null
                            ? null
                            : response.metadata().finishReason().name();
                    Integer tokensIn = null;
                    Integer tokensOut = null;
                    if (response.metadata() != null && response.metadata().tokenUsage() != null) {
                        tokensIn = response.metadata().tokenUsage().inputTokenCount();
                        tokensOut = response.metadata().tokenUsage().outputTokenCount();
                    }
                    long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                    historyService.saveAssistantMessage(
                            sessionId, answer.toString(), new ArrayList<>(sources), tokensIn, tokensOut, latencyMs);
                    send(emitter, "done", new ChatDoneEvent(finishReason, new ArrayList<>(sources)));
                    emitter.complete();
                })
                .onError(error -> {
                    send(emitter, "error", String.valueOf(error.getMessage()));
                    emitter.complete();
                })
                .start();
        return emitter;
    }

    @GetMapping("/chat/sessions/{sessionId}/messages")
    public List<ChatMessage> messages(@PathVariable String sessionId) {
        return historyService.messages(sessionId);
    }

    private static void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开等场景：忽略，让连接随 complete() 结束
        }
    }
}
