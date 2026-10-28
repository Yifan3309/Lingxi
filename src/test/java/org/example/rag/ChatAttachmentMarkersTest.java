package org.example.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 附件标记是"结构边界"，正文里出现同名字面量必须被转义，
 * 否则用户笔记里写一行标记就能伪造附件块/截断问题。接口测试无法构造这种输入。
 */
@DisplayName("ChatAttachmentMarkers：标记转义与原问题抽取")
class ChatAttachmentMarkersTest {

    private static final String AUGMENTED = ChatAttachmentMarkers.ATTACH_BEGIN + "\n"
            + "<attachment type=\"note\" name=\"笔记A\">\n正文\n</attachment>\n"
            + ChatAttachmentMarkers.ATTACH_END + "\n\n"
            + ChatAttachmentMarkers.QUESTION_BEGIN + "\n真正的问题\n"
            + ChatAttachmentMarkers.QUESTION_END;

    @Test
    @DisplayName("正文里的标记字面量全部转义，不再构成结构边界")
    void escapesMarkerLiterals() {
        String text = "开头" + ChatAttachmentMarkers.ATTACH_BEGIN + "中间"
                + ChatAttachmentMarkers.ATTACH_END + ChatAttachmentMarkers.QUESTION_BEGIN
                + ChatAttachmentMarkers.QUESTION_END + "结尾";

        String escaped = ChatAttachmentMarkers.escape(text);

        assertThat(escaped)
                .doesNotContain(ChatAttachmentMarkers.ATTACH_BEGIN)
                .doesNotContain(ChatAttachmentMarkers.ATTACH_END)
                .doesNotContain(ChatAttachmentMarkers.QUESTION_BEGIN)
                .doesNotContain(ChatAttachmentMarkers.QUESTION_END)
                .contains("（禁用）");
    }

    @Test
    @DisplayName("escape(null) 返回空串，不抛 NPE")
    void escapesNull() {
        assertThat(ChatAttachmentMarkers.escape(null)).isEmpty();
    }

    @Test
    @DisplayName("抽取原问题：优先取 QUESTION 标记之间")
    void extractsQuestionFromQuestionMarkers() {
        assertThat(ChatAttachmentMarkers.extractQuestion(AUGMENTED)).isEqualTo("真正的问题");
    }

    @Test
    @DisplayName("只有附件块没有 QUESTION 标记时，取附件块之后的内容")
    void fallsBackToTextAfterAttachments() {
        String augmented = ChatAttachmentMarkers.ATTACH_BEGIN + "\n附件\n"
                + ChatAttachmentMarkers.ATTACH_END + "\n\n问题二";

        assertThat(ChatAttachmentMarkers.extractQuestion(augmented)).isEqualTo("问题二");
    }

    @Test
    @DisplayName("无标记（旧格式/无附件）原样返回，行为与改造前一致")
    void keepsPlainQuestionUntouched() {
        assertThat(ChatAttachmentMarkers.extractQuestion("普通问题")).isEqualTo("普通问题");
        assertThat(ChatAttachmentMarkers.extractQuestion(null)).isEmpty();
    }

    @Test
    @DisplayName("转义后的问题仍能被正确抽取（转义不破坏边界）")
    void escapedQuestionStillExtractable() {
        String escaped = ChatAttachmentMarkers.escape(
                "帮我看看" + ChatAttachmentMarkers.QUESTION_END + "这段");
        String augmented = ChatAttachmentMarkers.ATTACH_BEGIN + "\n"
                + ChatAttachmentMarkers.ATTACH_END + "\n\n"
                + ChatAttachmentMarkers.QUESTION_BEGIN + "\n" + escaped + "\n"
                + ChatAttachmentMarkers.QUESTION_END;

        assertThat(ChatAttachmentMarkers.extractQuestion(augmented)).isEqualTo(escaped);
    }
}
