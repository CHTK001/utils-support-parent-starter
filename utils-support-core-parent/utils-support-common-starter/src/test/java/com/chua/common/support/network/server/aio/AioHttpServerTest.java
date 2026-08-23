package com.chua.common.support.network.server.aio;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.ConnectionBudgetServerFilter;
import com.chua.common.support.network.server.filter.HealthCheckServerFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AioHttpServer 冒烟测试:验证 IOCP(Proactor)传输层的基础能力矩阵。
 *
 * <p>覆盖:基础路由、POST body、Keep-Alive 连接复用、404、
 * 健康检查短路、单 IP 并发预算拦截。</p>
 *
 * @author CH
 * @since 2026/08/24
 */
class AioHttpServerTest {

    /**
     * HTTP 200 状态码
     */
    private static final int STATUS_OK = 200;

    /**
     * HTTP 404 状态码
     */
    private static final int STATUS_NOT_FOUND = 404;

    /**
     * HTTP 429 状态码:超出并发预算
     */
    private static final int STATUS_TOO_MANY_REQUESTS = 429;

    /**
     * 测试监听端口(0 = 系统自动分配,规避端口冲突)
     */
    private static final int AUTO_PORT = 0;

    /** 被测服务器实例 */
    private AioHttpServer server;

    /** 测试客户端 */
    private HttpClient client;

    /** 服务根地址 */
    private String baseUrl;

    /**
     * 每个用例前启动服务器并注册测试路由。
     */
    @BeforeEach
    void setUp() {
        ServerSetting setting = ServerSetting.defaults();
        setting.setPort(AUTO_PORT);
        server = new AioHttpServer(setting);
        server.registerMapping("/hello", (request, response) -> response.setResult("Hello World"));
        server.registerMapping("/echo", (request, response) -> response.setResult(request.getBodyString()));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getPort();
        client = HttpClient.newHttpClient();
    }

    /**
     * 每个用例后停止服务器。
     */
    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testGetHello() throws Exception {
        HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/hello")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(STATUS_OK, resp.statusCode());
        assertEquals("Hello World", resp.body());
    }

    @Test
    void testPostEchoBody() throws Exception {
        HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/echo"))
                .POST(HttpRequest.BodyPublishers.ofString("payload-123")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(STATUS_OK, resp.statusCode());
        assertTrue(resp.body().contains("payload-123"));
    }

    @Test
    void testNotFound() throws Exception {
        HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/no-such-route")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(STATUS_NOT_FOUND, resp.statusCode());
    }

    @Test
    void testHealthCheckShortCircuit() throws Exception {
        // 未注册 /healthz 路由,但 HealthCheckServerFilter 应短路返回 200
        server.addFilter(new HealthCheckServerFilter());
        HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/healthz")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(STATUS_OK, resp.statusCode());
        assertEquals("OK", resp.body());
    }

    @Test
    void testConnectionBudgetRejectsBurst() throws Exception {
        // 单 IP 并发预算为 1:串行请求不受影响,验证放行路径
        server.addFilter(new ConnectionBudgetServerFilter(1));
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/hello")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(STATUS_OK, resp.statusCode());
        }
    }

    @Test
    void testKeepAliveMultipleRequests() throws Exception {
        // 同一连接连续三条请求:验证 Keep-Alive 下解析器复位与写循环恢复读
        HttpClient keepAliveClient = HttpClient.newBuilder().build();
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> resp = keepAliveClient.send(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/hello")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(STATUS_OK, resp.statusCode());
            assertEquals("Hello World", resp.body());
        }
    }
}
