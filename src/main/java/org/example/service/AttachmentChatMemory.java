package org.example.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.example.rag.ChatAttachmentMarkers;

import java.util.List;

/**
 * 附件感知聊天记忆：把带附件标记的 UserMessage 存进窗口记忆前剥掉附件块，
 * 只留原始问题——附件只影响本轮，不随多轮累积撑爆上下文（MessageWindow 只按条数不按字数截断）。
 */
public class AttachmentChatMemory implements ChatMemory {

    private final ChatMemory delegate;

    public AttachmentChatMemory(ChatMemory delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object id() {
        return delegate.id();
    }

    @Override
    public void add(ChatMessage message) {
        delegate.add(sanitize(message));
    }

    @Override
    public void add(ChatMessage... messages) {
        for (ChatMessage m : messages) {
            add(m);
        }
    }

    @Override
    public void add(Iterable<ChatMessage> messages) {
        for (ChatMessage m : messages) {
            add(m);
        }
    }

    @Override
    public List<ChatMessage> messages() {
        return delegate.messages();
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    private static ChatMessage sanitize(ChatMessage message) {
        if (message instanceof UserMessage um && um.hasSingleText()) {
            String text = um.singleText();
            if (text.contains(ChatAttachmentMarkers.ATTACH_BEGIN)
                    || text.contains(ChatAttachmentMarkers.QUESTION_BEGIN)) {
                return UserMessage.from(ChatAttachmentMarkers.extractQuestion(text));
            }
        }
        return message;
    }
}
