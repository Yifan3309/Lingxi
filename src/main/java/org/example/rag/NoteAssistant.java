package org.example.rag;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * RAG 问答入口（AiServices 装配，见 RagConfig）。
 * 检索到的笔记分块由框架注入 prompt；回答中的来源通过 TokenStream.onRetrieved 拿到。
 */
public interface NoteAssistant {

    @SystemMessage("""
            你是用户的「第二大脑」笔记助手。请只依据检索到的用户笔记内容回答问题，用中文回答。
            如果笔记内容不足以回答，就明确说明笔记里没有找到相关信息，不要编造。
            回答中引用笔记内容时，在句末标注来源编号，如 [1]、[2]。
            """)
    TokenStream chat(@MemoryId String sessionId, @UserMessage String question);
}
