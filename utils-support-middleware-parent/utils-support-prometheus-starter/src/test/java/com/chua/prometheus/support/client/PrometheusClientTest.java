package com.chua.prometheus.support.client;

import com.chua.common.support.exception.RemoteExecutionException;
import com.chua.prometheus.support.engine.PrometheusEngine;
import com.chua.prometheus.support.model.PrometheusMetric;
import com.chua.prometheus.support.model.QueryResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PrometheusClient 单元测试
 * <p>
 * 使用 JDK 内置 HttpServer 模拟 Prometheus HTTP API, 不依赖外部服务,
 * 覆盖结果映射、参数透传、编码、失败语义与关闭守卫。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class PrometheusClientTest {

    /**
     * 各路径的桩响应
     */
    private static final Map<String, String> STUBS = new ConcurrentHashMap<>();

    /**
     * 各路径收到的原始查询串
     */
    private static final Map<String, String> RAW_QUERY = new ConcurrentHashMap<>();

    /**
     * 各路径返回的状态码
     */
    private static final Map<String, Integer> STATUS = new ConcurrentHashMap<>();

    /**
     * 桩服务
     */
    private static HttpServer server;

    /**
     * 桩服务地址
     */
    private static String baseUrl;

    /**
     * 启动桩服务
     */
    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", PrometheusClientTest::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 停止桩服务
     */
    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 处理桩请求
     *
     * @param exchange 请求上下文
     * @throws IOException 写出失败
     */
    private static void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        RAW_QUERY.put(path, exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery());
        String body = STUBS.get(path);
        int status = STATUS.getOrDefault(path, 200);
        if (body == null) {
            body = "{\"status\":\"error\",\"errorType\":\"not_found\",\"error\":\"no stub\"}";
            status = 404;
        }
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    /**
     * 创建客户端
     *
     * @return 指向桩服务的客户端
     */
    private static PrometheusClient newClient() {
        return PrometheusClient.builder().baseUrl(baseUrl + "/").timeoutMs(2000).build();
    }

    /**
     * 校验即时查询结果映射
     */
    @Test
    @DisplayName("vector 结果: 标签、字符串数值、浮点秒时间戳均正确映射")
    void parseVectorResult() {
        STUBS.put("/api/v1/query", "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                + "{\"metric\":{\"__name__\":\"up\",\"job\":\"prometheus\"},\"value\":[1701261657.078,\"1\"]},"
                + "{\"metric\":{\"__name__\":\"x\"},\"value\":[1701261657.078,\"NaN\"]}]}}");
        try (PrometheusClient client = newClient()) {
            QueryResult result = client.query("up").execute();
            assertEquals("vector", result.getResultType());
            assertEquals(2, result.getResult().size());
            PrometheusMetric first = result.getResult().get(0);
            assertEquals("up", first.getName());
            assertEquals("prometheus", first.getMetric().get("job"));
            assertEquals(1.0D, first.getValue(), 0.0D);
            assertEquals(1701261657L, first.getTimestamp());
            assertTrue(Double.isNaN(result.getResult().get(1).getValue()), "NaN 采样点不应被丢弃");
            assertEquals(1.0D, client.query("up").firstValue(), 0.0D);
        }
    }

    /**
     * 校验范围查询参数透传与 matrix 解析
     */
    @Test
    @DisplayName("query_range: query/start/end/step 全部透传, matrix 采样点时间戳正确")
    void parseMatrixResult() {
        STUBS.put("/api/v1/query_range", "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"__name__\":\"up\"},\"values\":[[1701261600.5,\"1\"],[1701261660,\"0\"]]}]}}");
        try (PrometheusClient client = newClient()) {
            QueryResult result = client.queryRange("up").range(1701261600L, 1701261720L).step(60L).execute();
            List<PrometheusMetric.Sample> samples = result.getResult().get(0).getValues();
            assertEquals(2, samples.size());
            assertEquals(1701261600L, samples.get(0).timestamp());
            assertEquals(0.0D, samples.get(1).value(), 0.0D);
            String raw = RAW_QUERY.get("/api/v1/query_range");
            assertTrue(raw.contains("start=1701261600") && raw.contains("end=1701261720") && raw.contains("step=60"), raw);
        }
        assertThrows(IllegalArgumentException.class, () -> {
            try (PrometheusClient client = newClient()) {
                client.queryRange("up").step(0).execute();
            }
        }, "step 非法必须显式拒绝");
    }

    /**
     * 校验 promql 编码与时间点透传
     */
    @Test
    @DisplayName("promql 按 URL 参数编码, time 参数透传")
    void encodeQueryAndForwardTime() {
        STUBS.put("/api/v1/query", "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");
        try (PrometheusClient client = newClient()) {
            client.query("rate(http_total{job=\"a b\"}[5m])").execute();
            assertTrue(RAW_QUERY.get("/api/v1/query")
                    .startsWith("query=rate%28http_total%7Bjob%3D%22a+b%22%7D%5B5m%5D%29"), RAW_QUERY.get("/api/v1/query"));
            client.query("up").time(1700000000L).execute();
            assertTrue(RAW_QUERY.get("/api/v1/query").endsWith("&time=1700000000"), RAW_QUERY.get("/api/v1/query"));
            assertEquals(baseUrl, client.baseUrl(), "尾部斜杠需规整, 避免拼接出 //api");
        }
    }

    /**
     * 校验标签名路径段编码
     */
    @Test
    @DisplayName("labelValues 对标签名做路径段编码, 空标签名显式拒绝")
    void encodeLabelName() {
        STUBS.put("/api/v1/label/job/values", "{\"status\":\"success\",\"data\":[\"prometheus\"]}");
        try (PrometheusClient client = newClient()) {
            assertEquals(List.of("prometheus"), client.labelValues("job"));
            assertThrows(IllegalArgumentException.class, () -> client.labelValues("  "));
        }
    }

    /**
     * 校验失败语义
     */
    @Test
    @DisplayName("HTTP 非 2xx、status=error、响应非 JSON 均抛出 RemoteExecutionException")
    void failExplicitly() {
        try (PrometheusClient client = newClient()) {
            STUBS.put("/api/v1/label/names", "{\"status\":\"success\",\"data\":[]}");
            STATUS.put("/api/v1/label/names", 500);
            assertThrows(RemoteExecutionException.class, client::labels, "HTTP 500 不能静默返回空");
            STATUS.remove("/api/v1/label/names");
            STUBS.put("/api/v1/label/names", "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"invalid\"}");
            RemoteExecutionException error = assertThrows(RemoteExecutionException.class, client::labels);
            assertTrue(error.getMessage().contains("bad_data") && error.getMessage().contains("invalid"), error.getMessage());
            STUBS.put("/api/v1/label/names", "<html>nginx</html>");
            assertThrows(RemoteExecutionException.class, client::labels, "非 JSON 响应不能静默返回空");
        }
    }

    /**
     * 校验关闭守卫
     */
    @Test
    @DisplayName("close 后调用抛出 IllegalStateException, 且 close 幂等")
    void guardAfterClose() {
        PrometheusClient client = newClient();
        client.close();
        client.close();
        assertTrue(client.isClosed());
        assertThrows(IllegalStateException.class, client::labels);
    }

    /**
     * 校验引擎生命周期
     */
    @Test
    @DisplayName("engine.close 释放底层客户端, 关闭后拒绝注册与取值")
    void engineReleasesClients() {
        PrometheusEngine engine = new PrometheusEngine();
        engine.addDataSource("default", baseUrl);
        PrometheusClient client = engine.client();
        engine.close();
        assertTrue(client.isClosed(), "引擎关闭必须连带关闭其登记的客户端");
        assertThrows(IllegalStateException.class, () -> engine.addDataSource("x", baseUrl));
        assertThrows(IllegalStateException.class, engine::client);
    }
}
