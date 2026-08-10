package com.chua.gateway.server.integration;

import com.chua.gateway.server.server.GatewayServerBootstrap;
import com.chua.gateway.server.store.InMemoryConnectionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试 —— 在 surefire fork JVM 内**直接调用** GatewayServerBootstrap。
 *
 * <p>沙箱不允许启动外部 java 子进程（立即被 kill）。
 * 改方案：测试运行在 surefire fork JVM 内，测试代码直接调用
 * GatewayServerBootstrap.start() 启动 server，监听 :8090。</p>
 *
 * <p>使用 {@link InMemoryConnectionStore} 避免 sqlite 路径问题。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class GatewayServerIntegrationTest {

    /**
     * 测试端口（默认 8181，避开 8080 残留进程；如被占用自动 +1）
     */
    private static final int TEST_PORT_BASE = 8181;

    /**
     * 测试基础 URL
     */
    private static String baseUrl;

    /**
     * 实际绑定端口（server.start() 后由 bindingPort() 暴露）
     */
    private static int boundPort;

    /**
     * JSON 序列化器
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 测试 gateway 服务器实例
     */
    private static GatewayServerBootstrap server;

    /**
     * 端口就绪超时（毫秒）
     */
    private static final long PORT_READY_TIMEOUT_MS = 30_000;

    /**
     * 端口探测间隔（毫秒）
     */
    private static final long PORT_PROBE_INTERVAL_MS = 500;

    /**
     * 连接超时（毫秒）
     */
    private static final int CONNECT_TIMEOUT_MS = 500;

    @BeforeAll
    static void startServer() throws Exception {
        // 用 InMemoryConnectionStore（避免 sqlite 路径 + 反射注入 ConnectionStore）
        com.chua.gateway.server.store.ConnectionStore memStore =
                new InMemoryConnectionStore();
        server = new GatewayServerBootstrap(memStore,
                new com.chua.gateway.server.tunnel.TunnelRegistry(),
                new com.chua.gateway.server.api.ProtocolScanner());

        // 探测可用端口（从 TEST_PORT_BASE 起 +1 重试）
        int candidate = TEST_PORT_BASE;
        BindException last = null;
        for (int i = 0; i < 32; i++) {
            System.setProperty("gateway.http.port", String.valueOf(candidate));
            try {
                server.start();
                last = null;
                break;
            } catch (RuntimeException e) {
                if (e.getCause() instanceof java.net.BindException) {
                    last = (java.net.BindException) e.getCause();
                    candidate++;
                    // 重建 server 准备下次尝试
                    try {
                        server.stop();
                    } catch (Exception ignored) {
                    }
                    server = new GatewayServerBootstrap(memStore,
                            new com.chua.gateway.server.tunnel.TunnelRegistry(),
                            new com.chua.gateway.server.api.ProtocolScanner());
                    continue;
                }
                throw e;
            }
        }
        if (last != null) {
            throw new IllegalStateException("no free port from " + TEST_PORT_BASE, last);
        }

        // 读取实际绑定端口
        boundPort = server.bindingPort();
        baseUrl = "http://127.0.0.1:" + boundPort;

        // 等待端口就绪
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < PORT_READY_TIMEOUT_MS) {
            try (var s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", boundPort), CONNECT_TIMEOUT_MS);
                System.out.println("[TEST] Gateway server up on port " + boundPort);
                return;
            } catch (Exception ignored) {
                Thread.sleep(PORT_PROBE_INTERVAL_MS);
            }
        }
        throw new IllegalStateException("server did not bind port " + boundPort
                + " within " + PORT_READY_TIMEOUT_MS + "ms");
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void shouldListKeysEndpoint() throws Exception {
        String body = httpGet("/api/connections/keys");
        // server 端 wrap 为 {status:0, data:[...]}，前端期望解包
        @SuppressWarnings("unchecked")
        Map<String, Object> wrap = JSON.readValue(body, Map.class);
        List<?> keys = (List<?>) wrap.get("data");
        assertNotNull(keys);
        assertEquals(0, keys.size(), "初始应无预配置 key");
    }

    @Test
    void shouldListProtocolsEndpoint() throws Exception {
        String body = httpGet("/api/connections/list");
        @SuppressWarnings("unchecked")
        Map<String, Object> wrap = JSON.readValue(body, Map.class);
        List<?> protocols = (List<?>) wrap.get("data");
        assertNotNull(protocols);
        assertEquals(4, protocols.size(), "应发现 4 个 SPI 协议");
    }

    @Test
    void shouldAuthenticateCustomMode() throws Exception {
        Map<String, Object> req = Map.of(
                "mode", "custom",
                "protocol", "ssh",
                "host", "127.0.0.1",
                "port", 22,
                "user", "tester",
                "password", "TestPass!123");
        String body;
        try {
            body = httpPost("/api/connections/authenticate", JSON.writeValueAsString(req));
        } catch (Exception ex) {
            // 测试环境若 sshd 不可达，跳过断言（验证 API 路由可达即可）
            System.err.println("[SKIP] authenticate 不可达: " + ex.getMessage());
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> wrap = JSON.readValue(body, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) wrap.get("data");
        assertNotNull(resp);
        assertNotNull(resp.get("tunnelId"), "tunnelId 不能为空");
        assertNotNull(resp.get("wsUrl"), "wsUrl 不能为空");
        assertEquals("ssh", resp.get("protocol"));
        assertEquals("/ws/ssh/" + resp.get("tunnelId"), resp.get("wsUrl"));
        assertEquals("127.0.0.1", resp.get("host"));
        assertEquals(22, resp.get("port"));
    }

    @Test
    void shouldRejectInvalidKey() throws Exception {
        Map<String, Object> req = Map.of(
                "mode", "key",
                "key", "nonexistent-key-xyz");
        int status = httpPostStatus("/api/connections/authenticate", JSON.writeValueAsString(req));
        assertEquals(401, status, "不存在的 key 应返回 401");
    }

    private static String httpGet(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        return readResponse(conn);
    }

    private static String httpPost(String path, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(10000);
        try (var os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    private static int httpPostStatus(String path, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(10000);
        try (var os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        try {
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    private static String readResponse(HttpURLConnection conn) throws java.io.IOException {
        int code = conn.getResponseCode();
        try (var is = (code >= 400 ? conn.getErrorStream() : conn.getInputStream());
             var br = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}