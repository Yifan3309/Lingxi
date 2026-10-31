package org.example.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分块器是纯函数，接口测试只能间接感受到"检索命中好/坏"，
 * 边界（超长段落硬切、重叠、空内容）只能靠单测锁行为。
 * 注意：这些断言描述的是**当前实现的契约**，改分块策略时要同步改，别当回归噪音。
 * 偏移契约：[charStart, charEnd) 指向原 content 中该块正文的连续区间，
 * substring 归一化空白后应包含块正文——GET /api/notes/{id}/chunks 的定位能力靠它。
 */
@DisplayName("NoteChunker：Markdown 分块规则与原文区间")
class NoteChunkerTest {

    private static String normalize(String s) {
        return s.replaceAll("\\s+", "");
    }

    @Test
    @DisplayName("无标题内容：整块前缀为「# 笔记标题」")
    void prefixesChunkWithNoteTitle() {
        List<NoteChunker.Chunk> chunks = NoteChunker.chunk("我的笔记", "第一段正文", 300);

        assertThat(chunks).extracting(NoteChunker.Chunk::text)
                .containsExactly("# 我的笔记\n\n第一段正文");
    }

    @Test
    @DisplayName("多级标题：块前缀携带完整标题路径，提升检索命中")
    void buildsHeadingPath() {
        List<NoteChunker.Chunk> chunks = NoteChunker.chunk("T", "# 总纲\n甲\n\n## 细节\n乙", 300);

        assertThat(chunks).extracting(NoteChunker.Chunk::text)
                .containsExactly(
                        "# T > 总纲\n\n甲",
                        "# T > 总纲 > 细节\n\n乙");
        assertThat(chunks).extracting(NoteChunker.Chunk::headingPath)
                .containsExactly("总纲", "总纲 > 细节");
    }

    @Test
    @DisplayName("标题为空：前缀退化为「未命名」，不抛异常")
    void fallsBackToUnnamedTitle() {
        List<NoteChunker.Chunk> chunks = NoteChunker.chunk("   ", "正文", 300);

        assertThat(chunks).extracting(NoteChunker.Chunk::text)
                .containsExactly("# 未命名\n\n正文");
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

        List<NoteChunker.Chunk> chunks = NoteChunker.chunk("T", paragraph, 300);

        String prefix = "# T\n\n";
        assertThat(chunks).hasSize(5);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.text()).startsWith(prefix);
            assertThat(c.text().length() - prefix.length()).isLessThanOrEqualTo(300);
        });
        // 第二块从第 200 字符开始（重叠 100 字符），区间同样落在原文对应位置
        assertThat(chunks.get(1).text().substring(prefix.length())).isEqualTo(paragraph.substring(200, 500));
        assertThat(chunks.get(1).charStart()).isEqualTo(200);
        assertThat(chunks.get(1).charEnd()).isEqualTo(500);
    }

    @Test
    @DisplayName("段落打包：超目标长度时开新块，并把上块末段作为重叠带过去")
    void carriesLastParagraphAsOverlap() {
        String p1 = "a".repeat(100);
        String p2 = "b".repeat(100);
        String p3 = "c".repeat(100);

        List<NoteChunker.Chunk> chunks = NoteChunker.chunk("T", p1 + "\n\n" + p2 + "\n\n" + p3, 300);

        assertThat(chunks).extracting(NoteChunker.Chunk::text)
                .containsExactly(
                        "# T\n\n" + p1 + "\n\n" + p2,
                        "# T\n\n" + p2 + "\n\n" + p3);
    }

    @Test
    @DisplayName("空段落与多余空行被丢弃，不产生空白分块")
    void dropsBlankParagraphs() {
        List<NoteChunker.Chunk> chunks = NoteChunker.chunk("T", "\n\n\n正文\n\n\n\n第二段\n\n", 300);

        assertThat(chunks).extracting(NoteChunker.Chunk::text)
                .containsExactly("# T\n\n正文\n\n第二段");
    }

    @Test
    @DisplayName("偏移往返：substring 区间去掉空白后包含块正文（多段 + 标题混杂）")
    void offsetsRoundTripThroughOriginalContent() {
        String content = "# 指南\n"
                + "开头一段话。\n"
                + "\n"
                + "## 安装\n"
                + "第一段讲安装步骤，比较长一些。\n"
                + "\n"
                + "第二段讲配置文件的位置与写法。\n"
                + "\n"
                + "### 进阶\n"
                + "第三段讲进阶用法。\n";

        List<NoteChunker.Chunk> chunks = NoteChunker.chunk("灵犀", content, 300);

        assertThat(chunks).isNotEmpty();
        for (NoteChunker.Chunk c : chunks) {
            String body = c.text().substring(c.text().indexOf("\n\n") + 2);
            assertThat(normalize(content.substring(c.charStart(), c.charEnd())))
                    .as("块「%s」的区间应覆盖其正文", body)
                    .isEqualTo(normalize(body));
        }
    }

    @Test
    @DisplayName("carry 重叠块的区间覆盖整块内容（从重叠段起点到末段终点，连续）")
    void carryChunkSpanCoversWholeBody() {
        String p1 = "a".repeat(100);
        String p2 = "b".repeat(100);
        String p3 = "c".repeat(100);
        String content = p1 + "\n\n" + p2 + "\n\n" + p3;

        List<NoteChunker.Chunk> chunks = NoteChunker.chunk("T", content, 300);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).charStart()).isEqualTo(102); // p2 起点
        assertThat(chunks.get(1).charEnd()).isEqualTo(304);   // p3 终点
        assertThat(normalize(content.substring(chunks.get(1).charStart(), chunks.get(1).charEnd())))
                .isEqualTo("b".repeat(100) + "c".repeat(100));
    }

    @Test
    @DisplayName("区间单调：后块起点不早于前块起点，且全部落在 [0, content.length] 内")
    void offsetsAreMonotonicAndBounded() {
        StringBuilder sb = new StringBuilder("# T\n");
        for (int i = 0; i < 8; i++) {
            sb.append("段落").append(i).append("：").append("字".repeat(120)).append("\n\n");
        }
        String content = sb.toString();

        List<NoteChunker.Chunk> chunks = NoteChunker.chunk("T", content, 300);

        assertThat(chunks).isNotEmpty();
        int prevStart = -1;
        for (NoteChunker.Chunk c : chunks) {
            assertThat(c.charStart()).isBetween(0, content.length());
            assertThat(c.charEnd()).isBetween(c.charStart(), content.length());
            assertThat(c.charStart()).isGreaterThanOrEqualTo(prevStart);
            prevStart = c.charStart();
        }
    }

    @Test
    @DisplayName("同一正文重算布局稳定：两次结果完全一致（index 对应 chunk_index 的前提）")
    void layoutIsDeterministic() {
        String content = "# A\n甲乙丙\n\n# B\n丁戊己";

        List<NoteChunker.Chunk> first = NoteChunker.chunk("T", content, 300);
        List<NoteChunker.Chunk> second = NoteChunker.chunk("T", content, 300);

        assertThat(first).usingRecursiveFieldByFieldElementComparator().isEqualTo(second);
    }
}
