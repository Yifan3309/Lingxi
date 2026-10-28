package org.example.rag;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索查询转换：3 万字附件不能污染查询向量（E5 只取前 512 token，会把真问题挤掉）。
 * 这是纯装配逻辑，接口测试只能从"答非所问"间接怀疑，测不出根因。
 */
@DisplayName("AttachmentQueryTransformer：召回前把增强提问还原成原问题")
class AttachmentQueryTransformerTest {

    @Test
    @DisplayName("带附件标记的提问 → 只用原问题去召回，检索元数据原样保留")
    void stripsAttachmentsBeforeRetrieval() {
        String augmented = ChatAttachmentMarkers.ATTACH_BEGIN + "\n"
                + "很长的附件正文".repeat(500) + "\n"
                + ChatAttachmentMarkers.ATTACH_END + "\n\n"
                + ChatAttachmentMarkers.QUESTION_BEGIN + "\n数据库迁移要注意什么？\n"
                + ChatAttachmentMarkers.QUESTION_END;

        Collection<Query> out = new AttachmentQueryTransformer().transform(Query.from(augmented, metadata()));

        assertThat(out).hasSize(1);
        Query transformed = out.iterator().next();
        assertThat(transformed.text()).isEqualTo("数据库迁移要注意什么？");
        assertThat(transformed.metadata().chatMemoryId()).isEqualTo("session-1");
    }

    @Test
    @DisplayName("无标记提问原样返回（无附件请求零改动）")
    void keepsPlainQuery() {
        Collection<Query> out = new AttachmentQueryTransformer()
                .transform(Query.from("普通问题", metadata()));

        assertThat(out).singleElement().satisfies(q -> assertThat(q.text()).isEqualTo("普通问题"));
    }

    private static Metadata metadata() {
        return Metadata.from(UserMessage.from("原始提问"), "session-1", List.of());
    }
}
