package org.example.web.dto;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.data.segment.TextSegment;

/**
 * 回答引用的来源：对应一条笔记分块，前端可据此跳转原笔记。
 * score 优先取重排分数（RERANKED_SCORE），无重排时为向量相似度（SCORE）。
 */
public record SourceRef(String noteId, String title, Integer chunkIndex, Double score, String excerpt) {

    public static SourceRef from(Content content) {
        TextSegment segment = content.textSegment();
        var metadata = segment.metadata();
        Double score = content.metadata() == null ? null : (Double) content.metadata().get(ContentMetadata.RERANKED_SCORE);
        if (score == null && content.metadata() != null) {
            score = (Double) content.metadata().get(ContentMetadata.SCORE);
        }
        String excerpt = segment.text().replace("\n", " ");
        if (excerpt.length() > 120) {
            excerpt = excerpt.substring(0, 120) + "…";
        }
        return new SourceRef(
                metadata.getString("note_id"),
                metadata.getString("title"),
                metadata.getInteger("chunk_index"),
                score,
                excerpt);
    }
}
