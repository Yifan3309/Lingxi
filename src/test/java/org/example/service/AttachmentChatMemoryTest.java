package org.example.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.example.rag.ChatAttachmentMarkers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多轮记忆只按条数截断、不按字数：若附件块随每轮累积进记忆，
 * 几轮之后上下文就被同一个附件撑爆。这属于"跑几轮才暴露"的问题，
 * 接口测试很难断言，必须直接检查记忆内容。
 */
@DisplayName("AttachmentChatMemory：附件块只进本轮，不进多轮记忆")
class AttachmentChatMemoryTest {

    private ChatMemory delegate;
    private AttachmentChatMemory memory;

    @BeforeEach
    void setUp() {
        delegate = MessageWindowChatMemory.withMaxMessages(20);
        memory = new AttachmentChatMemory(delegate);
    }

    @Test
    @DisplayName("存记忆前剥离附件块，只留原问题")
    void stripsAttachmentBlockBeforeStoring() {
        String augmented = ChatAttachmentMarkers.ATTACH_BEGIN + "\n"
                + "附件正文".repeat(1000) + "\n"
                + ChatAttachmentMarkers.ATTACH_END + "\n\n"
                + ChatAttachmentMarkers.QUESTION_BEGIN + "\n问题\n"
                + ChatAttachmentMarkers.QUESTION_END;

        memory.add(UserMessage.from(augmented));

        List<ChatMessage> messages = memory.messages();
        assertThat(messages).hasSize(1);
        assertThat(((UserMessage) messages.get(0)).singleText()).isEqualTo("问题");
        assertThat(((UserMessage) messages.get(0)).singleText())
                .doesNotContain(ChatAttachmentMarkers.ATTACH_BEGIN);
    }

    @Test
    @DisplayName("普通用户消息与 AI 回复原样保留")
    void keepsOrdinaryMessages() {
        memory.add(UserMessage.from("普通问题"), AiMessage.from("普通回答"));

        assertThat(memory.messages()).hasSize(2);
        assertThat(((UserMessage) memory.messages().get(0)).singleText()).isEqualTo("普通问题");
        assertThat(memory.messages().get(1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) memory.messages().get(1)).text()).isEqualTo("普通回答");
    }

    @Test
    @DisplayName("clear/窗口上限等能力原样委托给底层实现")
    void delegatesWindowBehaviour() {
        ChatMemory small = new AttachmentChatMemory(MessageWindowChatMemory.withMaxMessages(2));
        for (int i = 0; i < 4; i++) {
            small.add(UserMessage.from("第" + i + "轮"));
        }
        assertThat(small.messages()).hasSize(2);

        small.clear();
        assertThat(small.messages()).isEmpty();
    }
}
