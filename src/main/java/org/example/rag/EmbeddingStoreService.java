package org.example.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.example.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 向量库门面：持有进程内 InMemoryEmbeddingStore 实例，
 * 每次变更后整库序列化到文件（M1 数据量下开销可忽略）。
 * 换 PgVector/Milvus 时仅需替换本类实现，检索方无感。
 * 附带笔记质心索引（note_id → 各 chunk 向量均值，M3 图谱语义边用）：
 * addAll/removeAllByNoteId 是全部写入入口，索引随之增量维护；启动时从序列化 JSON 重建。
 */
@Service
public class EmbeddingStoreService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStoreService.class);

    private final Path storePath;
    private final InMemoryEmbeddingStore<TextSegment> store;
    private final Object persistLock = new Object();
    private final Map<String, float[]> noteCentroids = new ConcurrentHashMap<>();

    public EmbeddingStoreService(RagProperties props) {
        this.storePath = Path.of(props.storePath());
        if (Files.exists(storePath)) {
            this.store = InMemoryEmbeddingStore.fromFile(storePath);
            rebuildCentroidsFromJson();
            log.info("已从 {} 加载向量库（笔记质心 {} 篇）", storePath, noteCentroids.size());
        } else {
            this.store = new InMemoryEmbeddingStore<>();
            log.info("向量库为空，将在 {} 新建", storePath);
        }
    }

    public InMemoryEmbeddingStore<TextSegment> store() {
        return store;
    }

    /** 笔记质心只读视图：key=noteId。 */
    public Map<String, float[]> noteCentroids() {
        return Map.copyOf(noteCentroids);
    }

    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        store.addAll(ids, embeddings, segments);
        updateCentroids(segments, embeddings);
        persist();
    }

    public void removeAllByNoteId(String noteId) {
        store.removeAll(metadataKey("note_id").isEqualTo(noteId));
        noteCentroids.remove(noteId);
        persist();
    }

    /** 同批按 note_id 分组重算质心（ingest 流程是先 removeAll 再 addAll，此处重算保证幂等）。 */
    private void updateCentroids(List<TextSegment> segments, List<Embedding> embeddings) {
        Map<String, double[]> sums = new ConcurrentHashMap<>();
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            String noteId = segments.get(i).metadata().getString("note_id");
            if (noteId == null) {
                continue;
            }
            float[] v = embeddings.get(i).vector();
            double[] sum = sums.computeIfAbsent(noteId, k -> new double[v.length]);
            for (int d = 0; d < v.length; d++) {
                sum[d] += v[d];
            }
            counts.merge(noteId, 1, Integer::sum);
        }
        for (var e : sums.entrySet()) {
            int n = counts.get(e.getKey());
            float[] c = new float[e.getValue().length];
            for (int d = 0; d < c.length; d++) {
                c[d] = (float) (e.getValue()[d] / n);
            }
            noteCentroids.put(e.getKey(), c);
        }
    }

    /** 无公开迭代 API：经序列化 JSON 重建质心索引（启动一次，容错——失败只降级图谱语义边）。 */
    private void rebuildCentroidsFromJson() {
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(store.serializeToJson());
            Map<String, double[]> sums = new ConcurrentHashMap<>();
            Map<String, Integer> counts = new ConcurrentHashMap<>();
            for (var entry : root.path("entries")) {
                String noteId = entry.path("embedded").path("metadata").path("metadata").path("note_id").asText(null);
                var vector = entry.path("embedding").path("vector");
                if (noteId == null || !vector.isArray() || vector.isEmpty()) {
                    continue;
                }
                double[] sum = sums.computeIfAbsent(noteId, k -> new double[vector.size()]);
                for (int d = 0; d < vector.size(); d++) {
                    sum[d] += vector.get(d).asDouble();
                }
                counts.merge(noteId, 1, Integer::sum);
            }
            for (var e : sums.entrySet()) {
                int n = counts.get(e.getKey());
                float[] c = new float[e.getValue().length];
                for (int d = 0; d < c.length; d++) {
                    c[d] = (float) (e.getValue()[d] / n);
                }
                noteCentroids.put(e.getKey(), c);
            }
        } catch (Exception e) {
            log.warn("笔记质心索引重建失败（图谱语义边将缺失）：{}", String.valueOf(e.getMessage()));
        }
    }

    private void persist() {
        synchronized (persistLock) {
            try {
                if (storePath.getParent() != null) {
                    Files.createDirectories(storePath.getParent());
                }
                store.serializeToFile(storePath);
            } catch (IOException e) {
                // 持久化失败不阻断检索链路，但要让问题可见
                throw new UncheckedIOException("向量库写入失败: " + storePath, e);
            }
        }
    }
}
