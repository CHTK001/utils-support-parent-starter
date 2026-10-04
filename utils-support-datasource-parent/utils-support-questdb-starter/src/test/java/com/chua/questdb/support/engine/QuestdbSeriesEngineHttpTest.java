package com.chua.questdb.support.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QuestdbSeriesEngine 真实 HTTP 往返测试：以 JDK HttpServer 模拟 QuestDB 的
 * {@code /write}（ILP over HTTP）与 {@code /exec}（REST）端点，验证写入线路格式、
 * 指标发现、曲线查询降采样与保留期清理。
 *
 * @author CH
 * @since 4.0.0.42
 */
class QuestdbSeriesEngineHttpTest {

    /**
     * 模拟 QuestDB 服务
     */
    private HttpServer server;

    /**
     * 被测引擎
     */
    private QuestdbSeriesEngine engine;

    /**
     * 最近一次 /write 请求体
     */
    private final AtomicReference<String> writeBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/write", this::handleWrite);
        server.createContext("/exec", this::handleExec);
        server.start();
        engine = new QuestdbSeriesEngine("http://127.0.0.1:" + server.getAddress().getPort(), null, null, null);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void configuredEngineIsAvailable() {
        assertTrue(engine.isAvailable());
        assertEquals("questdb", engine.engine());
    }

    @Test
    void writePointSendsInfluxLineProtocol() {
        assertDoesNotThrow(() -> engine.writePoint("acc_t", 7L, "cpu_usage", 12.5, 1_700_000_000_000L));
        String body = writeBody.get();
        assertNotNull(body);
        assertTrue(body.contains("acc_t"), "写入体应包含 measurement: " + body);
        assertTrue(body.contains("monitor_id=7"), "写入体应包含监控目标 tag: " + body);
        assertTrue(body.contains("cpu_usage=12.5"), "写入体应包含指标值: " + body);
    }

    @Test
    void metricsDiscoversNumericColumns() {
        List<String> metrics = engine.metrics("acc_t", 1L);
        assertEquals(List.of("cpu_usage", "mem_usage"), metrics);
    }

    @Test
    void metricsReturnsEmptyWhenTableMissing() {
        List<String> metrics = engine.metrics("missing_t", 1L);
        assertNotNull(metrics);
        assertTrue(metrics.isEmpty());
    }

    @Test
    void seriesDownsamplesToWindowAverage() {
        List<List<Object>> curve = engine.series("acc_t", 1L, "cpu_usage", 1, "1m");
        assertNotNull(curve);
        assertEquals(1, curve.size(), "同窗口内的点应聚合为一个桶: " + curve);
        assertEquals(20.0D, ((Number) curve.get(0).get(1)).doubleValue(), 0.0001D);
    }

    @Test
    void seriesWithoutMetricAmbiguousThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> engine.series("acc_t", 1L, null, 1, "1m"));
    }

    @Test
    void seriesReturnsEmptyWhenTableMissing() {
        List<List<Object>> curve = engine.series("missing_t", 1L, "cpu_usage", 1, "1m");
        assertNotNull(curve);
        assertTrue(curve.isEmpty());
    }

    @Test
    void purgeReturnsMinusOneForExecutedDelete() {
        assertEquals(-1, engine.purge("acc_t", 1L, "cpu_usage", 1_700_000_000_000L));
    }

    @Test
    void purgeReturnsZeroWhenTableMissing() {
        assertEquals(0, engine.purge("missing_t", 1L, "cpu_usage", 1_700_000_000_000L));
    }

    /**
     * 处理 /write：记录请求体并返回 204。
     *
     * @param exchange HTTP 交换
     * @throws IOException IO 异常
     */
    private void handleWrite(HttpExchange exchange) throws IOException {
        writeBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    /**
     * 处理 /exec：按查询内容返回预置 JSON。
     *
     * @param exchange HTTP 交换
     * @throws IOException IO 异常
     */
    private void handleExec(HttpExchange exchange) throws IOException {
        String query = decodedQuery(exchange);
        // 表不存在优先于语句类型：真实 QuestDB 对缺失表的 SELECT/DELETE 一律返回该错误
        if (query.contains("missing_t")) {
            respond(exchange, 400, "{\"query\":\""
                    + escape(query) + "\",\"error\":\"table does not exist: missing_t\",\"position\":15}");
            return;
        }
        if (query.startsWith("DELETE")) {
            respond(exchange, 200, "{\"ddl\":\"OK\"}");
            return;
        }
        if (query.contains("cpu_usage") && !query.startsWith("SELECT * ")) {
            respond(exchange, 200, seriesJson());
            return;
        }
        respond(exchange, 200, probeJson());
    }

    /**
     * 读取并解码 query 参数。
     *
     * @param exchange HTTP 交换
     * @return 解码后的 SQL
     */
    private String decodedQuery(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return "";
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "query".equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    /**
     * 写回 JSON 响应。
     *
     * @param exchange HTTP 交换
     * @param status   HTTP 状态码
     * @param body     响应体
     * @throws IOException IO 异常
     */
    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    /**
     * 表结构样本响应：timestamp/monitor_id + 两个数值指标列。
     *
     * @return JSON
     */
    private String probeJson() {
        return "{\"query\":\"SELECT * FROM acc_t LIMIT 1\","
                + "\"columns\":["
                + "{\"name\":\"timestamp\",\"type\":\"TIMESTAMP\"},"
                + "{\"name\":\"monitor_id\",\"type\":\"SYMBOL\"},"
                + "{\"name\":\"cpu_usage\",\"type\":\"DOUBLE\"},"
                + "{\"name\":\"mem_usage\",\"type\":\"DOUBLE\"}],"
                + "\"timestamp\":0,"
                + "\"dataset\":[[\"2026-01-01T00:00:00.000000Z\",\"1\",12.5,55.0]],"
                + "\"count\":1}";
    }

    /**
     * 曲线响应：同一分钟内的三个点，窗口聚合后均值应为 20。
     *
     * @return JSON
     */
    private String seriesJson() {
        return "{\"query\":\"SELECT timestamp, cpu_usage FROM acc_t\","
                + "\"columns\":["
                + "{\"name\":\"timestamp\",\"type\":\"TIMESTAMP\"},"
                + "{\"name\":\"cpu_usage\",\"type\":\"DOUBLE\"}],"
                + "\"timestamp\":0,"
                + "\"dataset\":["
                + "[\"2026-01-01T00:00:00.000000Z\",10.0],"
                + "[\"2026-01-01T00:00:01.000000Z\",20.0],"
                + "[\"2026-01-01T00:00:02.000000Z\",30.0]],"
                + "\"count\":3}";
    }

    /**
     * 简单转义查询串中的引号，保证返回 JSON 合法。
     *
     * @param value 原始串
     * @return 转义后串
     */
    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
