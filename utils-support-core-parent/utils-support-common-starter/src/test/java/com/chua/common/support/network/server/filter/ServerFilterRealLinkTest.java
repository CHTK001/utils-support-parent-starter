package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.nio.NioHttpServer;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端过滤器的真实 HTTP 链路用例。
 *
 * <p>每个用例起一个真实的 {@link NioHttpServer}，用 JDK {@link HttpClient} 发请求，
 * 断言线上响应（状态码/响应头/是否绕过 handler）。不直接操作过滤器对象，
 * 避免「单测通过、链路没接上」的假覆盖。</p>
 *
 * <p>只覆盖名称与实现稳定的过滤器；正在改名的
 * {@code Cors/Gzip/RateLimit/RequestId/RequestTimeout/ResponseRewrite}
 * 不在此列。</p>
 *
 * @author CH
 */
@Timeout(60)
class ServerFilterRealLinkTest {

    @Test
    @DisplayName("SecurityHeaders：真实响应带安全头")
    void securityHeadersAreApplied() throws Exception {
        try (Server server = Server.start(new SecurityHeadersServerFilter())) {
            HttpResponse<String> resp = server.get("/echo");
            assertEquals(200, resp.statusCode());
            assertEquals("nosniff", resp.headers().firstValue("X-Content-Type-Options").orElse(null));
            assertEquals("DENY", resp.headers().firstValue("X-Frame-Options").orElse(null));
            assertEquals("strict-origin-when-cross-origin",
                    resp.headers().firstValue("Referrer-Policy").orElse(null));
        }
    }

    @Test
    @DisplayName("BodySizeLimit：超限 413，未超限放行")
    void bodySizeLimitRejectsOversized() throws Exception {
        try (Server server = Server.start(new BodySizeLimitServerFilter(64))) {
            HttpResponse<String> rejected = server.post("/echo", "x".repeat(200));
            assertEquals(413, rejected.statusCode(), "超过 64 字节应回 413");

            HttpResponse<String> allowed = server.post("/echo", "ok");
            assertEquals(200, allowed.statusCode(), "2 字节应放行");
        }
    }

    @Test
    @DisplayName("HealthCheck：/healthz 由过滤器直接回 200，不进入业务 handler")
    void healthCheckBypassesHandler() throws Exception {
        ServerHandler sentinel = new ServerHandler() {
            @Override
            public void handle(ServerRequest request, ServerResponse response) {
                response.setStatus(418).setContentType("text/plain").setBody("business-handler");
                response.end();
            }
        };
        try (Server server = Server.start(sentinel, new HealthCheckServerFilter())) {
            HttpResponse<String> health = server.get("/healthz");
            assertEquals(200, health.statusCode(), "/healthz 应由过滤器处理");
            assertTrue(!health.body().contains("business-handler"), "健康检查不应落到业务 handler");

            // 非健康路径仍走业务 handler（418 是这里故意设的哨兵）
            HttpResponse<String> business = server.get("/echo");
            assertEquals(418, business.statusCode(), "非健康路径应走业务 handler，实际 body=" + business.body());
        }
    }

    @Test
    @DisplayName("IpServerFilter：黑名单 IP 回 403")
    void ipBlacklistBlocksClient() throws Exception {
        IpServerFilter filter = IpServerFilter.blacklist();
        filter.addBlacklist("127.0.0.1");
        try (Server server = Server.start(filter)) {
            HttpResponse<String> resp = server.get("/echo");
            assertEquals(403, resp.statusCode());
            assertTrue(resp.body().contains("Forbidden"), "403 响应体应带 Forbidden: " + resp.body());
        }
    }

    @Test
    @DisplayName("ConnectionBudget：同一 IP 并发超限回 429")
    void connectionBudgetRejectsConcurrent() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        ServerHandler slow = new ServerHandler() {
            @Override
            public void handle(ServerRequest request, ServerResponse response) throws Exception {
                entered.countDown();
                Thread.sleep(700L);
                response.setStatus(200).setBody("ok");
                response.end();
            }
        };        try (Server server = Server.start(slow, new ConnectionBudgetServerFilter(1))) {
            CompletableFuture<HttpResponse<String>> first =
                    server.client().sendAsync(server.request("/echo"), HttpResponse.BodyHandlers.ofString());
            if (!entered.await(5, TimeUnit.SECONDS)) {
                HttpResponse<String> peek = first.get(10, TimeUnit.SECONDS);
                org.junit.jupiter.api.Assertions.fail(
                        "第一个请求应已进入 handler，实际状态=" + peek.statusCode() + " body=" + peek.body());
            }

            HttpResponse<String> second = newClient().send(
                    server.request("/echo"), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> firstResp = first.get(10, TimeUnit.SECONDS);

            int firstStatus = firstResp.statusCode();
            int secondStatus = second.statusCode();
            assertTrue((firstStatus == 200 && secondStatus == 429)
                            || (firstStatus == 429 && secondStatus == 200),
                    "并发超限应是一个 200 一个 429，实际: " + firstStatus + "/" + secondStatus);
        }
    }

    /**
     * 一个真实运行的 HTTP 服务包装，负责端口、过滤器、handler 与客户端。
     */
    private static final class Server implements AutoCloseable {

        private final NioHttpServer server;
        private final HttpClient client;

        private Server(NioHttpServer server, HttpClient client) {
            this.server = server;
            this.client = client;
        }

        /**
         * 用默认 echo handler 起服务。
         *
         * @param filters 过滤器
         * @return 运行中的服务
         * @throws Exception 启动失败
         */
        static Server start(ServerFilter... filters) throws Exception {
            return start(defaultHandler(), filters);
        }

        /**
         * 用指定 handler 起服务。
         *
         * @param handler 业务 handler
         * @param filters 过滤器
         * @return 运行中的服务
         * @throws Exception 启动失败
         */
        static Server start(ServerHandler handler, ServerFilter... filters) throws Exception {
            int port = freePort();
            ServerSetting setting = ServerSetting.builder()
                    .protocol("http")
                    .host("127.0.0.1")
                    .port(port)
                    .auto(false)
                    .build();
            NioHttpServer http = new NioHttpServer(setting);
            http.registerMapping("/echo", HttpMethod.GET, handler);
            http.registerMapping("/echo", HttpMethod.POST, handler);
            for (ServerFilter filter : filters) {
                http.addFilter(filter);
            }
            http.start();
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            return new Server(http, client);
        }

        /**
         * 默认业务 handler。
         *
         * @return handler
         */
        private static ServerHandler defaultHandler() {
            return new ServerHandler() {
                @Override
                public void handle(ServerRequest request, ServerResponse response) {
                    response.setStatus(200).setContentType("text/plain").setBody("echo:" + request.getPath());
                    response.end();
                }
            };
        }

        /**
         * 发 GET。
         *
         * @param path 路径
         * @return 响应
         * @throws Exception 请求失败
         */
        HttpResponse<String> get(String path) throws Exception {
            return client.send(request(path), HttpResponse.BodyHandlers.ofString());
        }

        /**
         * 发 POST。
         *
         * @param path 路径
         * @param body 请求体
         * @return 响应
         * @throws Exception 请求失败
         */
        HttpResponse<String> post(String path, String body) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(uri(path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return client.send(req, HttpResponse.BodyHandlers.ofString());
        }

        /**
         * 构造 GET 请求。
         *
         * @param path 路径
         * @return 请求
         */
        HttpRequest request(String path) {
            return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build();
        }

        /**
         * 客户端实例。
         *
         * @return client
         */
        HttpClient client() {
            return client;
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + server.getPort() + path);
        }

        @Override
        public void close() {
            server.stop();
        }
    }

    /**
     * 取一个空闲端口。
     *
     * @return 端口
     * @throws Exception 绑定失败
     */
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 新建一个独立 HttpClient（强制新连接，避免 HTTP/1.1 单连接串行化）。
     *
     * @return 客户端
     */
    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }
}
