package org.example.web;

import dev.langchain4j.service.TokenStream;
import jakarta.servlet.http.HttpServletRequest;
import org.example.domain.ChatMessage;
import org.example.rag.ChatAttachmentService;
import org.example.rag.NoteAssistant;
import org.example.service.ChatHistoryService;
import org.example.web.dto.ChatAttachment;
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
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RAG 问答：基于全部笔记检索后回答，SSE 流式返回；会话与消息落库（M2）。
 * 事件约定：message = 增量 token；done = 结束帧（finishReason + 来源列表 sources）；
 * error = 失败帧。sessionId 为空时用 "default"，多轮上下文按会话隔离（内存态，重启即清；
 * 历史消息在库中可回看）。
 * @附件：request.attachments 解析为本轮上下文（不入库）；历史只存原问题。
 * 笔记类附件并入 done.sources（按 noteId 去重，重排分数优先）。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final NoteAssistant noteAssistant;
    private final ChatHistoryService historyService;
    private final ChatAttachmentService attachmentService;

    public ChatController(NoteAssistant noteAssistant, ChatHistoryService historyService,
                          ChatAttachmentService attachmentService) {
        this.noteAssistant = noteAssistant;
        this.historyService = historyService;
        this.attachmentService = attachmentService;
    }

    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        if (request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? "default"
                : request.sessionId();
        SseEmitter emitter = new SseEmitter(180_000L);
        List<SourceRef> ragSources = new CopyOnWriteArrayList<>();
        List<SourceRef> attachmentSources = new ArrayList<>();
        StringBuilder answer = new StringBuilder();
        long startNanos = System.nanoTime();

        // 附件解析（校验失败直接 400/403 抛出，不进模型）；历史只存原问题
        List<ChatAttachment> attachments = request.attachments();
        List<ChatAttachmentService.ResolvedAttachment> resolved = attachments == null || attachments.isEmpty()
                ? List.of()
                : attachmentService.resolve(attachments, isLoopback(httpRequest.getRemoteAddr()));
        for (ChatAttachmentService.ResolvedAttachment r : resolved) {
            attachmentSources.addAll(r.refs());
        }
        String prompt = attachmentService.augment(request.question(), resolved);

        historyService.saveUserMessage(sessionId, request.question());

        TokenStream stream = noteAssistant.chat(sessionId, prompt);
        // 思考增量（ollama thinking / deepseek reasoning）：langchain4j 1.22 单独回调、默认无人订阅即丢失。
        // 用 <think> 围栏包进 message 流——前端按折叠框解析，持久化形态与内联思考的模型（deepseek-r1）一致；
        // AiMessage.thinking 独立于 content，多轮记忆不会把思考带进后续提问。
        // 围栏开关用原子布尔：thinking 与 content 回调可能来自不同线程，普通数组元素无跨线程可见性保证。
        AtomicBoolean thinkFenceOpen = new AtomicBoolean(false);
        stream
                .onPartialThinking(thinking -> {
                    String t = thinking.text();
                    if (t == null || t.isEmpty()) {
                        return;
                    }
                    if (thinkFenceOpen.compareAndSet(false, true)) {
                        answer.append("<think>\n");
                        send(emitter, "message", "<think>\n");
                    }
                    answer.append(t);
                    send(emitter, "message", t);
                })
                .onPartialResponse(token -> {
                    if (thinkFenceOpen.compareAndSet(true, false)) {
                        answer.append("\n</think>\n\n");
                        send(emitter, "message", "\n</think>\n\n");
                    }
                    answer.append(token);
                    send(emitter, "message", token);
                })
                .onRetrieved(contents -> contents.forEach(c -> ragSources.add(SourceRef.from(c))))
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
                    List<SourceRef> sources = mergeSources(ragSources, attachmentSources);
                    historyService.saveAssistantMessage(
                            sessionId, answer.toString(), sources, tokensIn, tokensOut, latencyMs);
                    send(emitter, "done", new ChatDoneEvent(finishReason, sources));
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

    /** 来源合并：RAG 重排分数优先保留，附件独有笔记按首次出现追加。 */
    static List<SourceRef> mergeSources(List<SourceRef> rag, List<SourceRef> extra) {
        Map<String, SourceRef> byId = new LinkedHashMap<>();
        for (SourceRef s : rag) {
            if (s.noteId() != null) {
                byId.put(s.noteId(), s);
            }
        }
        for (SourceRef s : extra) {
            if (s.noteId() != null) {
                byId.putIfAbsent(s.noteId(), s);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** 附件本地文件仅放行 loopback（127.0.0.1 / ::1），防局域网访问时变成任意文件读取器。 */
    static boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        if ("127.0.0.1".equals(remoteAddr) || "::1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr) || "localhost".equalsIgnoreCase(remoteAddr)) {
            return true;
        }
        try {
            return InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private static void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开等场景：忽略，让连接随 complete() 结束
        }
    }
}

