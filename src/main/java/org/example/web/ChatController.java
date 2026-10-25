package org.example.web;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.service.TokenStream;
import org.example.rag.NoteAssistant;
import org.example.web.dto.ChatDoneEvent;
import org.example.web.dto.ChatRequest;
import org.example.web.dto.SourceRef;
import org.springframework.http.HttpStatus;
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
 * RAG 问答：基于全部笔记检索后回答，SSE 流式返回。
 * 事件约定：message = 增量 token；done = 结束帧（finishReason + 来源列表 sources）；
 * error = 失败帧。sessionId 为空时用 "default"，多轮上下文按会话隔离（内存态，重启即清）。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final NoteAssistant noteAssistant;

    public ChatController(NoteAssistant noteAssistant) {
        this.noteAssistant = noteAssistant;
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

        TokenStream stream = noteAssistant.chat(sessionId, request.question());
        stream
                .onPartialResponse(token -> send(emitter, "message", token))
                .onRetrieved(contents -> contents.forEach(c -> sources.add(toRef(c))))
                .onCompleteResponse(response -> {
                    String finishReason = response.metadata() == null || response.metadata().finishReason() == null
                            ? null
                            : response.metadata().finishReason().name();
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

    private static SourceRef toRef(Content content) {
        TextSegment segment = content.textSegment();
        var metadata = segment.metadata();
        Double score = content.metadata() == null ? null : (Double) content.metadata().get(ContentMetadata.SCORE);
        String excerpt = segment.text().replace("\n", " ");
        if (excerpt.length() > 120) {
            excerpt = excerpt.substring(0, 120) + "…";
        }
        return new SourceRef(
                metadata.getString("note_id"),
                metadata.getString("title"),
                metadata.getInteger("chunk_index"),
                score,
                excerpt);
    }

    private static void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开等场景：忽略，让连接随 complete() 结束
        }
    }
}
