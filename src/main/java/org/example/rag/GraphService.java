package org.example.rag;

import org.example.config.GraphProperties;
import org.example.domain.Note;
import org.example.repository.NoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱（M3）：节点 = 笔记 + 标签；边 = 共标签（结构边）+ 语义近邻（质心余弦 ≥ 阈值的 top-K/笔记）。
 * 质心来自 EmbeddingStoreService 的笔记质心索引（内存两两余弦，500 篇规模亚秒级）。
 * 阈值默认 0.90：E5 中文短文本余弦基线偏高（不相关对 ~0.85），见 GraphProperties 说明。
 */
@Service
public class GraphService {

    private static final Logger log = LoggerFactory.getLogger(GraphService.class);

    private final NoteRepository noteRepo;
    private final EmbeddingStoreService storeService;
    private final GraphProperties props;

    public GraphService(NoteRepository noteRepo, EmbeddingStoreService storeService, GraphProperties props) {
        this.noteRepo = noteRepo;
        this.storeService = storeService;
        this.props = props;
    }

    public record GraphNode(String id, String label, String type, String folder, double size) {}

    public record GraphEdge(String source, String target, String type, double weight) {}

    public record GraphResult(List<GraphNode> nodes, List<GraphEdge> edges) {}

    /** 语义边阈值允许的调节区间（图谱页「关联度」按钮的可调范围） */
    public static final double MIN_SCORE_FLOOR = 0.85;
    public static final double MIN_SCORE_CEIL = 0.99;

    /**
     * @param minScore 语义边阈值覆盖；null = 用 yml 配置值。无论来源都钳位在 [0.85, 0.99]。
     */
    public GraphResult graph(Double minScore) {
        double threshold = clampThreshold(minScore != null ? minScore : props.semanticMinScore());
        List<Note> notes = noteRepo.findAll();
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Map<String, Integer> tagCount = new HashMap<>();

        for (Note n : notes) {
            String title = n.getTitle();
            nodes.add(new GraphNode(n.getId(), title == null || title.isBlank() ? "未命名" : title,
                    "note", n.getFolder() == null ? "" : n.getFolder(), nodeSize(n)));
            for (String tag : splitTags(n.getTags())) {
                tagCount.merge(tag, 1, Integer::sum);
                edges.add(new GraphEdge(n.getId(), "tag:" + tag, "cotag", 1));
            }
        }
        for (var e : tagCount.entrySet()) {
            nodes.add(new GraphNode("tag:" + e.getKey(), e.getKey(), "tag", "", 6 + e.getValue() * 2));
        }

        addSemanticEdges(notes, edges, threshold);
        log.info("图谱生成：{} 节点 / {} 边", nodes.size(), edges.size());
        return new GraphResult(nodes, edges);
    }

    /** 质心两两余弦，每笔记只留最高的 top-K 条（无质心的笔记跳过）。 */
    private void addSemanticEdges(List<Note> notes, List<GraphEdge> edges, double threshold) {
        Map<String, float[]> centroids = storeService.noteCentroids();
        record Candidate(String a, String b, double score) {}
        Map<String, List<Candidate>> perNote = new HashMap<>();
        for (int i = 0; i < notes.size(); i++) {
            float[] a = centroids.get(notes.get(i).getId());
            if (a == null) {
                continue;
            }
            for (int j = i + 1; j < notes.size(); j++) {
                float[] b = centroids.get(notes.get(j).getId());
                if (b == null) {
                    continue;
                }
                double score = cosine(a, b);
                if (score >= threshold) {
                    perNote.computeIfAbsent(notes.get(i).getId(), k -> new ArrayList<>())
                            .add(new Candidate(notes.get(i).getId(), notes.get(j).getId(), score));
                    perNote.computeIfAbsent(notes.get(j).getId(), k -> new ArrayList<>())
                            .add(new Candidate(notes.get(j).getId(), notes.get(i).getId(), score));
                }
            }
        }
        java.util.Set<String> emitted = new java.util.HashSet<>();
        for (var e : perNote.entrySet()) {
            e.getValue().sort((x, y) -> Double.compare(y.score(), x.score()));
            for (int k = 0; k < Math.min(props.semanticTopK(), e.getValue().size()); k++) {
                Candidate c = e.getValue().get(k);
                String key = c.a().compareTo(c.b()) < 0 ? c.a() + "|" + c.b() : c.b() + "|" + c.a();
                if (emitted.add(key)) {
                    edges.add(new GraphEdge(c.a(), c.b(), "semantic", c.score()));
                }
            }
        }
    }

    private static double clampThreshold(double v) {
        return Math.max(MIN_SCORE_FLOOR, Math.min(MIN_SCORE_CEIL, v));
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int d = 0; d < a.length; d++) {
            dot += (double) a[d] * b[d];
            na += (double) a[d] * a[d];
            nb += (double) b[d] * b[d];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 节点大小按内容体量（100 字起步，封顶 30），图谱上有层次感 */
    private static double nodeSize(Note n) {
        int len = (n.getContent() == null ? 0 : n.getContent().length())
                + (n.getTitle() == null ? 0 : n.getTitle().length());
        return 10 + Math.min(20, len / 200.0);
    }

    private static List<String> splitTags(String tags) {
        List<String> out = new ArrayList<>();
        if (tags != null) {
            for (String t : tags.split("[,，]")) {
                if (!t.isBlank()) {
                    out.add(t.trim());
                }
            }
        }
        return out;
    }
}
