package org.example.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/**
 * 可热切换的模型包装：Bean 实例恒定（AiServices/控制器持有的引用不变），
 * 切换时仅替换内部委托——进行中的请求继续用旧模型，新请求走新模型。
 *
 * 关键：必须覆盖接口的 chat 入口方法整体委托（而非只委托 doChat）——
 * 接口 default 实现会用「调用者自身」的 defaultRequestParameters() 重打包请求，
 * 在包装上执行会丢失具体模型厂商的参数类型，导致目标模型 doChat 内强转 ClassCastException。
 */
public final class SwappableModels {

    private SwappableModels() {
    }

    public static class Chat implements ChatModel {
        private volatile ChatModel target;

        public Chat(ChatModel initial) {
            this.target = initial;
        }

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            return target.chat(chatRequest);
        }

        @Override
        public ChatResponse chat(ChatRequest chatRequest, ChatRequestOptions options) {
            return target.chat(chatRequest, options);
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            return target.doChat(chatRequest);
        }

        public void swap(ChatModel newTarget) {
            this.target = newTarget;
        }
    }

    public static class Streaming implements StreamingChatModel {
        private volatile StreamingChatModel target;

        public Streaming(StreamingChatModel initial) {
            this.target = initial;
        }

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            target.chat(request, handler);
        }

        @Override
        public void chat(ChatRequest request, ChatRequestOptions options, StreamingChatResponseHandler handler) {
            target.chat(request, options, handler);
        }

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            target.doChat(chatRequest, handler);
        }

        public void swap(StreamingChatModel newTarget) {
            this.target = newTarget;
        }
    }
}
