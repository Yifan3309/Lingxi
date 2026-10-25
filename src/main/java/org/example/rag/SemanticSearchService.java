package org.example.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.example.config.RagProperties;
import org.example.web.dto.RelatedNote;
import org.example.web.dto.SourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 语义搜索（M2）：向量召回（带模型前缀）→ 可选重排 → 按分数排序返回。
 * - search：面向用户查询，查询与重排可用。
 * - relatedNotes：以笔记自身内容为查询做相关推荐；无自然查询语义，不重排，
 *   按笔记去重取最高块分。
 */
@Service
public class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> store;
    private final RagProperties props;
    private final ObjectProvider<ScoringModel> reranker;

    public SemanticSearchService(EmbeddingModel embeddingModel,
                                 EmbeddingStoreService storeService,
                                 RagProperties props,
                                 ObjectProvider<ScoringModel> reranker) {
        this.embeddingModel = embeddingModel;
        this.store = storeService.store();
        this.props = props;
        this.reranker = reranker;
    }

    public List<SourceRef> search(String query, int limit) {
        int candidates = props.rerankerEnabled()
                ? Math.max(limit * props.recallMultiplier(), limit)
                : limit;
        Embedding queryEmbedding = embeddingModel.embed(props.queryPrefix() + query).content();
        return recall(queryEmbedding, candidates, null)
                .rerankWith(query)
                .limitTo(limit);
    }

    public List<RelatedNote> relatedNotes(String noteId, String title, String content, int limit) {
        String queryText = (title == null ? "" : title) + "\n" + (content == null ? "" : content);
        if (queryText.length() > 1000) {
            queryText = queryText.substring(0, 1000);
        }
        Embedding queryEmbedding = embeddingModel.embed(props.queryPrefix() + queryText).content();

        Map<String, RelatedNote> best = new LinkedHashMap<>();
        for (SourceRef hit : recall(queryEmbedding, limit * 3, noteId).toList()) {
            if (hit.noteId() == null || hit.noteId().equals(noteId)) {
                continue;
            }
            best.merge(hit.noteId(), new RelatedNote(hit.noteId(), hit.title(), hit.score()),
                    (a, b) -> a.score() != null && b.score() != null && a.score() >= b.score() ? a : b);
        }
        return best.values().stream()
                .sorted(Comparator.comparing(RelatedNote::score,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
    }

    /** 向量召回一批候选（可排除某笔记）。 */
    private Recall recall(Embedding queryEmbedding, int candidates, String excludeNoteId) {
        Filter filter = excludeNoteId == null ? null : metadataKey("note_id").isNotEqualTo(excludeNoteId);
        EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(candidates)
                .filter(filter)
                .build());
        return new Recall(result);
    }

    /** 召回结果容器：可选用 cross-encoder 重排，再按分数截断。 */
    private final class Recall {
        private final List<TextSegment> segments;
        private final List<Double> scores;

        Recall(EmbeddingSearchResult<TextSegment> result) {
            this.segments = result.matches().stream().map(EmbeddingMatch::embedded).toList();
            this.scores = result.matches().stream()
                    .map(m -> m.score() == null ? 0.0 : m.score())
                    .toList();
        }

        Recall rerankWith(String query) {
            if (segments.isEmpty() || query == null || query.isBlank()) {
                return this;
            }
            ScoringModel scoringModel = reranker.getIfAvailable();
            if (scoringModel == null) {
                return this;
            }
            List<Double> newScores;
            try {
                newScores = scoringModel.scoreAll(segments, query).content();
            } catch (Exception e) {
                // 远程 reranker（sidecar）掉线等情况：降级为向量分数直出，检索不中断
                log.warn("重排失败，跳过重排（按向量分数直出）：{}", String.valueOf(e.getMessage()));
                return this;
            }
            // 分数已 sigmoid 归一到 0-1；低于阈值（用户可见的百分数尺度）的候选剔除
            List<Integer> kept = new ArrayList<>();
            for (int i = 0; i < newScores.size(); i++) {
                Double s = newScores.get(i);
                if (s == null || s >= props.rerankMinScore()) {
                    kept.add(i);
                }
            }
            return new Recall(
                    kept.stream().map(segments::get).toList(),
                    kept.stream().map(newScores::get).toList());
        }

        List<SourceRef> toList() {
            return java.util.stream.IntStream.range(0, segments.size())
                    .mapToObj(i -> toRef(segments.get(i), scores.get(i)))
                    .sorted(Comparator.comparing(SourceRef::score,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        }

        List<SourceRef> limitTo(int limit) {
            List<SourceRef> all = toList();
            return all.size() <= limit ? all : all.subList(0, limit);
        }

        private Recall(List<TextSegment> segments, List<Double> scores) {
            this.segments = segments;
            this.scores = scores;
        }
    }

    private static SourceRef toRef(TextSegment segment, Double score) {
        var metadata = segment.metadata();
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
