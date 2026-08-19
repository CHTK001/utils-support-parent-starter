package com.chua.quarkus.support.server;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quarkus HTTP 服务器功能测试。
 *
 * <p>使用 JDK 内置的 {@link HttpClient} 测试 Quarkus 服务器的路由匹配、
 * 请求解析和响应构建等核心功能。</p>
 *
 * @author CH
 */
class QuarkusHttpServerTest {

    /**
     * 端口号
     */
    private static int port;

    /**
     * 服务器实例
     */
    private static QuarkusHttpServer server;

    /**
     * http Client
     */
    private static HttpClient httpClient;

    @BeforeAll
    /** Setup */
    static void setup() throws Exception {
        port = findAvailablePort();
        ServerSetting setting = new ServerSetting();
        setting.setPort(port);
        setting.setHost("127.0.0.1");
        setting.setWorkerThreads(2);

        server = new QuarkusHttpServer(setting);

        server.registerMapping("/hello", (req, resp) -> {
            resp.setBody("Hello, Quarkus!");
            resp.setContentType("text/plain");
            resp.end();
        });

        server.registerMapping("/echo", (req, resp) -> {
            String body = req.getBodyString();
            resp.setBody("echo: " + body);
            resp.setContentType("text/plain");
            resp.end();
        });

        server.start();

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @AfterAll
    /** Teardown */
    static void teardown() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("GET /hello 返回 200 + body")
    void testGetHello() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/hello"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("Hello, Quarkus!", response.body());
    }

    @Test
    @DisplayName("POST /echo 回显请求体")
    void testPostEcho() throws Exception {
        String requestBody = "test data";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/echo"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("echo: " + requestBody, response.body());
    }

    @Test
    @DisplayName("404 未找到路由")
    void testNotFound() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/nonexistent"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
    }

    @Test
    @DisplayName("服务器生命周期")
    void testServerLifecycle() throws Exception {
        int testPort = findAvailablePort();
        ServerSetting setting = new ServerSetting();
        setting.setPort(testPort);
        setting.setHost("127.0.0.1");
        QuarkusHttpServer testServer = new QuarkusHttpServer(setting);
        testServer.start();
        assertTrue(testServer.isRunning());
        testServer.stop();
        assertFalse(testServer.isRunning());
    }

    /**
     * 查找可用的 TCP 端口。
     *
     * @return 可用端口号
     * @throws IOException IO 异常
     */
    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
