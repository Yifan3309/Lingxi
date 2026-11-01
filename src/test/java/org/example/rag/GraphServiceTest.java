package org.example.rag;

import org.example.TestFixtures;
import org.example.config.GraphProperties;
import org.example.domain.Note;
import org.example.repository.NoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 图谱是"看起来对就行"的可视化产物，接口测试只断言 200 与节点数，
 * 但边方向/阈值过滤/top-K 去重错了用户只会觉得"图乱"。这里把构图规则钉死：
 * 共标签双向边、语义边阈值、每笔记 top-K、同一对只出一条边。
 */
@DisplayName("GraphService：共标签边与语义边构图规则")
class GraphServiceTest {

    private final NoteRepository noteRepo = mock(NoteRepository.class);
    private final EmbeddingStoreService store = mock(EmbeddingStoreService.class);

    @Test
    @DisplayName("共标签：每篇笔记指向标签节点，标签节点大小随引用次数增长")
    void buildsCotagEdgesAndTagNodes() {
        givenNotes(
                TestFixtures.note("n1", "笔记一", "AI,笔记", "工作", "内容"),
                TestFixtures.note("n2", "笔记二", "AI", null, "内容"));

        GraphService.GraphResult result = service(new GraphProperties(0.9, 2)).graph(null);

        assertThat(result.nodes()).filteredOn(n -> "tag".equals(n.type()))
                .extracting(GraphService.GraphNode::id)
                .containsExactlyInAnyOrder("tag:AI", "tag:笔记");
        assertThat(result.nodes()).filteredOn(n -> "tag:AI".equals(n.id()))
                .singleElement()
                .satisfies(n -> assertThat(n.size()).isEqualTo(6 + 2 * 2));
        assertThat(result.edges()).filteredOn(e -> "cotag".equals(e.type()))
                .extracting(GraphService.GraphEdge::source)
                .containsExactlyInAnyOrder("n1", "n1", "n2");
    }

    @Test
    @DisplayName("语义边：质心余弦达到阈值才连，权重为余弦值")
    void connectsSemanticallySimilarNotes() {
        givenNotes(
                TestFixtures.note("n1", "笔记一", null, null, "内容"),
                TestFixtures.note("n2", "笔记二", null, null, "内容"));
        givenCentroids(Map.of("n1", new float[]{1, 0}, "n2", new float[]{1, 0}));

        List<GraphService.GraphEdge> semantic = semanticEdges(new GraphProperties(0.9, 2));

        assertThat(semantic).hasSize(1);
        assertThat(semantic.get(0).weight()).isEqualTo(1.0);
        assertThat(List.of(semantic.get(0).source(), semantic.get(0).target()))
                .containsExactlyInAnyOrder("n1", "n2");
    }

    @Test
    @DisplayName("语义边：低于阈值（如正交向量）不连边")
    void filtersSemanticEdgesBelowThreshold() {
        givenNotes(
                TestFixtures.note("n1", "笔记一", null, null, "内容"),
                TestFixtures.note("n2", "笔记二", null, null, "内容"));
        givenCentroids(Map.of("n1", new float[]{1, 0}, "n2", new float[]{0, 1}));

        assertThat(semanticEdges(new GraphProperties(0.9, 2))).isEmpty();
    }

    @Test
    @DisplayName("语义边：每笔记最多 semanticTopK 条，同一对笔记只出一条")
    void limitsSemanticEdgesPerNote() {
        givenNotes(
                TestFixtures.note("n1", "一", null, null, "内容"),
                TestFixtures.note("n2", "二", null, null, "内容"),
                TestFixtures.note("n3", "三", null, null, "内容"));
        givenCentroids(Map.of(
                "n1", new float[]{1, 0}, "n2", new float[]{1, 0}, "n3", new float[]{1, 0}));

        assertThat(semanticEdges(new GraphProperties(0.9, 1))).hasSize(2);
    }

    @Test
    @DisplayName("语义边：没有质心的笔记（如嵌入失败）跳过，不影响其它边")
    void skipsNotesWithoutCentroid() {
        givenNotes(
                TestFixtures.note("n1", "一", null, null, "内容"),
                TestFixtures.note("n2", "二", null, null, "内容"),
                TestFixtures.note("n3", "三", null, null, "内容"));
        givenCentroids(Map.of("n1", new float[]{1, 0}, "n2", new float[]{1, 0}));

        List<GraphService.GraphEdge> semantic = semanticEdges(new GraphProperties(0.9, 2));

        assertThat(semantic).hasSize(1);
        assertThat(semantic).extracting(GraphService.GraphEdge::target).doesNotContain("n3");
    }

    @Test
    @DisplayName("标题为空时节点标签显示「未命名」，不留空白节点")
    void labelsEmptyTitleAsUnnamed() {
        givenNotes(TestFixtures.note("n1", null, null, null, "内容"));

        GraphService.GraphResult result = service(new GraphProperties(0.9, 2)).graph(null);

        assertThat(result.nodes()).singleElement()
                .satisfies(n -> assertThat(n.label()).isEqualTo("未命名"));
    }

    private GraphService service(GraphProperties props) {
        return new GraphService(noteRepo, store, props);
    }

    private void givenNotes(Note... notes) {
        when(noteRepo.findAll()).thenReturn(List.of(notes));
    }

    private void givenCentroids(Map<String, float[]> centroids) {
        when(store.noteCentroids()).thenReturn(centroids);
    }

    private List<GraphService.GraphEdge> semanticEdges(GraphProperties props) {
        return semanticEdges(props, null);
    }

    private List<GraphService.GraphEdge> semanticEdges(GraphProperties props, Double minScore) {
        return service(props).graph(minScore).edges().stream()
                .filter(e -> "semantic".equals(e.type()))
                .toList();
    }

    @Test
    @DisplayName("语义边阈值参数覆盖 yml：0.85 时连上 0.85~0.90 之间的对")
    void minScoreParamOverridesYml() {
        givenNotes(
                TestFixtures.note("n1", "笔记一", null, null, "内容"),
                TestFixtures.note("n2", "笔记二", null, null, "内容"));
        // 两篇质心余弦 = 0.87：低于 yml 的 0.9，但落在参数钳位下限 0.85 之上
        givenCentroids(Map.of("n1", new float[]{1, 0}, "n2", new float[]{0.87f, 0.4930525f}));

        assertThat(semanticEdges(new GraphProperties(0.9, 2), 0.85)).hasSize(1);
        assertThat(semanticEdges(new GraphProperties(0.9, 2), null)).isEmpty();
    }

    @Test
    @DisplayName("参数钳位到 0.85~0.99：过低按 0.85、过高按 0.99 处理")
    void minScoreParamIsClamped() {
        givenNotes(
                TestFixtures.note("n1", "笔记一", null, null, "内容"),
                TestFixtures.note("n2", "笔记二", null, null, "内容"));
        givenCentroids(Map.of("n1", new float[]{1, 0}, "n2", new float[]{0.8f, 0.6f}));

        // 余弦 0.8：钳到 0.85 后连不上；钳到 0.99 更连不上
        assertThat(semanticEdges(new GraphProperties(0.9, 2), 0.5)).isEmpty();
        assertThat(semanticEdges(new GraphProperties(0.9, 2), 1.2)).isEmpty();
    }
}
