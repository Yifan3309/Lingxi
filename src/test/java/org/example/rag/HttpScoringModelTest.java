package org.example.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 重排 HTTP 适配层：协议字段、批量顺序、返回数量校验。
 * 真机验证需要 GPU sidecar 在跑，接口测试只能覆盖"能答"这条主路径；
 * 返回值条数不对、服务 500 这类边界必须用本地 stub 服务构造。
 */
@DisplayName("HttpScoringModel：sidecar 协议与异常校验")
class HttpScoringModelTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private volatile String responseBody = "{\"scores\":[0.5]}";
    private volatile int status = 200;
    private volatile int calls;

    @BeforeEach
    void startStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/score", exchange -> {
            calls++;
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStubServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("单条打分：请求体带 query/texts，响应分数原样返回（已是 sigmoid 尺度）")
    void scoresSingleText() throws Exception {
        responseBody = "{\"scores\":[0.42]}";

        Double score = model().score("候选文本", "用户查询").content();

        assertThat(score).isEqualTo(0.42);
        JsonNode sent = mapper.readTree(requestBodies.get(0));
        assertThat(sent.get("query").asText()).isEqualTo("用户查询");
        assertThat(sent.get("texts")).hasSize(1);
        assertThat(sent.get("texts").get(0).asText()).isEqualTo("候选文本");
    }

    @Test
    @DisplayName("批量打分：一次请求带全部文本，分数与输入顺序一一对应")
    void scoresAllInOneRequest() {
        responseBody = "{\"scores\":[0.1,0.2,0.3]}";

        List<Double> scores = model().scoreAll(List.of(
                TextSegment.from("一"), TextSegment.from("二"), TextSegment.from("三")), "查询").content();

        assertThat(scores).containsExactly(0.1, 0.2, 0.3);
        assertThat(calls).isEqualTo(1);
    }

    @Test
    @DisplayName("空候选列表：不发起 HTTP 请求")
    void skipsRequestForEmptySegments() {
        assertThat(model().scoreAll(List.of(), "查询").content()).isEmpty();
        assertThat(calls).isZero();
    }

    @Test
    @DisplayName("返回分数条数与输入不符：抛异常（避免错位挂到别的分块上）")
    void rejectsSizeMismatch() {
        responseBody = "{\"scores\":[0.5]}";

        assertThatThrownBy(() -> model().scoreAll(List.of(
                TextSegment.from("一"), TextSegment.from("二")), "查询"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("期望 2 个分数");
    }

    @Test
    @DisplayName("sidecar 返回 5xx：异常上抛，由调用方决定降级（搜索跳重排/RAG 回退）")
    void propagatesServerError() {
        status = 500;
        responseBody = "boom";

        assertThatThrownBy(() -> model().score("文本", "查询")).isInstanceOf(RuntimeException.class);
    }

    private HttpScoringModel model() {
        return new HttpScoringModel("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
