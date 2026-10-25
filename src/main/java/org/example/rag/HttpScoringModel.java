package org.example.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * 远程 reranker 适配（Python sidecar：reranker-service/）。ScoringModel → POST /score。
 * 服务端为 bge-reranker-v2-m3 + CUDA EP + 跨请求动态批处理（多请求攒成一批进 GPU），
 * 分数已 sigmoid 归一到 0-1，与本地 OnnxScoringModel(normalize=true) 同尺度——
 * rerank-min-score 阈值与前端百分数展示语义不变。调用方（ReRankingContentAggregator、
 * SemanticSearchService）面向 ScoringModel 接口，无感知切换。
 */
public class HttpScoringModel implements ScoringModel {

    public record ScoreRequest(String query, List<String> texts) {}

    public record ScoreResponse(List<Double> scores) {}

    private final RestClient client;

    public HttpScoringModel(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    public Response<Double> score(String text, String other) {
        return Response.from(scoreBatch(other, List.of(text)).get(0));
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        if (segments == null || segments.isEmpty()) {
            return Response.from(List.of());
        }
        return Response.from(scoreBatch(query, segments.stream().map(TextSegment::text).toList()));
    }

    private List<Double> scoreBatch(String query, List<String> texts) {
        ScoreResponse resp = client.post()
                .uri("/score")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ScoreRequest(query, texts))
                .retrieve()
                .body(ScoreResponse.class);
        if (resp == null || resp.scores() == null || resp.scores().size() != texts.size()) {
            throw new IllegalStateException("reranker 服务返回异常：期望 " + texts.size()
                    + " 个分数，得到 " + (resp == null ? "null" : resp.scores().size()));
        }
        return resp.scores();
    }
}
