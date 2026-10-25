package org.example.observability;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 可观测性：记录每次 LLM 调用的模型名、token 用量与延迟。
 * 耗时通过 context attributes 在 onRequest/onResponse 间传递。
 */
@Component
public class TokenUsageLoggingListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageLoggingListener.class);
    private static final String START_NANOS = "startNanos";

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        requestContext.attributes().put(START_NANOS, System.nanoTime());
        log.info("LLM 请求 model={}", requestContext.chatRequest().modelName());
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        long latencyMs = elapsedMs(responseContext.attributes());
        TokenUsage usage = responseContext.chatResponse().metadata() == null
                ? null
                : responseContext.chatResponse().metadata().tokenUsage();
        log.info("LLM 响应 model={} 耗时={}ms finishReason={} inputTokens={} outputTokens={} totalTokens={}",
                responseContext.chatRequest().modelName(),
                latencyMs,
                responseContext.chatResponse().metadata() == null ? null : responseContext.chatResponse().metadata().finishReason(),
                usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount(),
                usage == null ? null : usage.totalTokenCount());
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        log.warn("LLM 调用失败 model={} 耗时={}ms error={}",
                errorContext.chatRequest().modelName(),
                elapsedMs(errorContext.attributes()),
                String.valueOf(errorContext.error().getMessage()));
    }

    private long elapsedMs(java.util.Map<Object, Object> attributes) {
        Object start = attributes.get(START_NANOS);
        return start == null ? -1 : (System.nanoTime() - (long) start) / 1_000_000;
    }
}
