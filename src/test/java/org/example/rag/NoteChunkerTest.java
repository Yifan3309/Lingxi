package org.example.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分块器是纯函数，接口测试只能间接感受到"检索命中好/坏"，
 * 边界（超长段落硬切、重叠、空内容）只能靠单测锁行为。
 * 注意：这些断言描述的是**当前实现的契约**，改分块策略时要同步改，别当回归噪音。
 */
@DisplayName("NoteChunker：Markdown 分块规则")
class NoteChunkerTest {

    @Test
    @DisplayName("无标题内容：整块前缀为「# 笔记标题」")
    void prefixesChunkWithNoteTitle() {
        List<String> chunks = NoteChunker.chunk("我的笔记", "第一段正文", 300);

        assertThat(chunks).containsExactly("# 我的笔记\n\n第一段正文");
    }

    @Test
    @DisplayName("多级标题：块前缀携带完整标题路径，提升检索命中")
    void buildsHeadingPath() {
        List<String> chunks = NoteChunker.chunk("T", "# 总纲\n甲\n\n## 细节\n乙", 300);

        assertThat(chunks).containsExactly(
                "# T > 总纲\n\n甲",
                "# T > 总纲 > 细节\n\n乙");
    }

    @Test
    @DisplayName("标题为空：前缀退化为「未命名」，不抛异常")
    void fallsBackToUnnamedTitle() {
        List<String> chunks = NoteChunker.chunk("   ", "正文", 300);

        assertThat(chunks).containsExactly("# 未命名\n\n正文");
    }

    @Test
    @DisplayName("空内容 / 只有标题没正文：不产生任何分块")
    void emptyContentProducesNothing() {
        assertThat(NoteChunker.chunk("T", "", 300)).isEmpty();
        assertThat(NoteChunker.chunk("T", null, 300)).isEmpty();
        assertThat(NoteChunker.chunk("T", "## 只有标题", 300)).isEmpty();
    }

    @Test
    @DisplayName("超长段落：按 step=目标-100 硬切，块间保留重叠，单块正文不超目标长度")
    void hardSplitsOverlongParagraph() {
        String paragraph = "x".repeat(1000);

        List<String> chunks = NoteChunker.chunk("T", paragraph, 300);

        String prefix = "# T\n\n";
        assertThat(chunks).hasSize(5);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c).startsWith(prefix);
            assertThat(c.length() - prefix.length()).isLessThanOrEqualTo(300);
        });
        // 第二块从第 200 字符开始（重叠 100 字符）
        assertThat(chunks.get(1).substring(prefix.length())).isEqualTo(paragraph.substring(200, 500));
    }

    @Test
    @DisplayName("段落打包：超目标长度时开新块，并把上块末段作为重叠带过去")
    void carriesLastParagraphAsOverlap() {
        String p1 = "a".repeat(100);
        String p2 = "b".repeat(100);
        String p3 = "c".repeat(100);

        List<String> chunks = NoteChunker.chunk("T", p1 + "\n\n" + p2 + "\n\n" + p3, 300);

        assertThat(chunks).containsExactly(
                "# T\n\n" + p1 + "\n\n" + p2,
                "# T\n\n" + p2 + "\n\n" + p3);
    }

    @Test
    @DisplayName("空段落与多余空行被丢弃，不产生空白分块")
    void dropsBlankParagraphs() {
        List<String> chunks = NoteChunker.chunk("T", "\n\n\n正文\n\n\n\n第二段\n\n", 300);

        assertThat(chunks).containsExactly("# T\n\n正文\n\n第二段");
    }
}
