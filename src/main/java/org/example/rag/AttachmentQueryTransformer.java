package org.example.rag;

import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;

import java.util.Collection;
import java.util.List;

/**
 * 附件感知检索查询转换：把带附件标记的增强提问还原为原始问题再去召回/重排。
 * 没有标记时原样返回——无附件请求与旧行为完全一致。
 * 目的：3 万字附件正文不能污染查询向量（E5 只取前 512 token，会把真正的问题挤掉）。
 */
public class AttachmentQueryTransformer implements QueryTransformer {

    @Override
    public Collection<Query> transform(Query query) {
        return List.of(Query.from(ChatAttachmentMarkers.extractQuestion(query.text()), query.metadata()));
    }
}
