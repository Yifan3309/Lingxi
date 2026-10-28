package org.example.web;

import org.example.web.dto.SourceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个纯函数：来源合并（RAG 命中 vs 附件附加）与 loopback 判定。
 * 前者决定前端"来源"卡片是否重复/串味，后者是本地文件附件的安全边界——
 * 越过边界意味着局域网访问者能读本机任意文件，必须精确。
 */
@DisplayName("ChatController：来源合并与本机判定")
class ChatControllerTest {

    @Test
    @DisplayName("同一篇笔记既被检索命中又被 @ 附加：只留一条，取重排分数更高的 RAG 版本")
    void ragSourceWinsOnDuplicateNote() {
        SourceRef rag = new SourceRef("n1", "RAG 标题", 0, 0.93, "检索片段");
        SourceRef attachment = new SourceRef("n1", "附件标题", null, null, "附件片段");

        List<SourceRef> merged = ChatController.mergeSources(List.of(rag), List.of(attachment));

        assertThat(merged).containsExactly(rag);
    }

    @Test
    @DisplayName("附件独有笔记按首次出现顺序追加；noteId 为空的来源被丢弃")
    void appendsAttachmentOnlyNotes() {
        SourceRef rag = new SourceRef("n1", "RAG", 0, 0.9, "x");
        SourceRef extra1 = new SourceRef("n2", "附件二", null, null, "y");
        SourceRef extra2 = new SourceRef("n3", "附件三", null, null, "z");
        SourceRef noId = new SourceRef(null, "本地文件", null, null, "w");

        List<SourceRef> merged = ChatController.mergeSources(List.of(rag), List.of(extra1, noId, extra2));

        assertThat(merged).extracting(SourceRef::noteId).containsExactly("n1", "n2", "n3");
    }

    @Test
    @DisplayName("两侧都为空时返回空列表，不返回 null")
    void handlesEmptyInputs() {
        assertThat(ChatController.mergeSources(List.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("loopback 判定：本机各种写法为真，其它地址为假")
    void detectsLoopback() {
        assertThat(ChatController.isLoopback("127.0.0.1")).isTrue();
        assertThat(ChatController.isLoopback("::1")).isTrue();
        assertThat(ChatController.isLoopback("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(ChatController.isLoopback("localhost")).isTrue();

        assertThat(ChatController.isLoopback("192.168.1.20")).isFalse();
        assertThat(ChatController.isLoopback("10.0.0.5")).isFalse();
        assertThat(ChatController.isLoopback((String) null)).isFalse();
        assertThat(ChatController.isLoopback("  ")).isFalse();
    }
}
