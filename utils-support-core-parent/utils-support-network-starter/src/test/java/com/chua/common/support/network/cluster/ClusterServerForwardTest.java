package com.chua.common.support.network.cluster;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.scatter.ScatterSyncHelper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ClusterServer 端到端 HTTP 转发测试。
 *
 * <p>验证：curl 请求 Node A 的 HTTP 端口 → ServiceDiscoveryServerFilter 选目标 →
 * ReverseProxyServerFilter 代理转发 → 拿到 Node B 后端响应。</p>
 */
public class ClusterServerForwardTest {

    /** node-B 的纯 HTTP 后端（不经 ClusterServer） */
    private HttpServer backendB;
    private int backendBPort;

    /** node-A 和 node-B 的 ClusterServer */
    private ClusterServer nodeA;
    private ClusterServer nodeB;

    @BeforeEach
    void setUp() throws Exception {
        // 重置 scatter 静态状态，并清除所有服务表缓存（防止跨测试污染）
        ScatterSyncHelper.resetForTest();
        // 清除已启动节点的服务表（如果有）
        if (nodeA != null) nodeA.discovery().clearCache();
        if (nodeB != null) nodeB.discovery().clearCache();

        // ── ① 启动 node-B 的纯 HTTP 后端 ─────────────────────────────
        CountDownLatch backendReady = new CountDownLatch(1);
        backendB = HttpServer.create(new InetSocketAddress(0), 0);
        // 注册路径与请求路径一致（proxy 不剥离前缀）
        backendB.createContext("/api/hello", exchange -> {
            byte[] body = "Hello from node-B".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            backendReady.countDown();
        });
        backendB.setExecutor(null);
        backendB.start();
        backendBPort = backendB.getAddress().getPort();
        backendReady.countDown();

        // ── ② 启动 node-B（作为 seed，提供 discovery 服务） ──────────
        nodeB = ClusterServer.builder()
                .nodeId("node-b").host("127.0.0.1").port(0)
                .scatterId("cluster-forward")
                .servicePaths(java.util.List.of("/api"))
                .timeoutMillis(3000)
                .build();
        nodeB.start();

        // ── ③ 启动 node-A，seeds 指向 node-B 的 scatter 端口（httpPort+2）──
        int scatterPortB = nodeB.discovery().getSetting().getPort();
        nodeA = ClusterServer.builder()
                .nodeId("node-a").host("127.0.0.1").port(0)
                .scatterId("cluster-forward")
                .seeds("127.0.0.1:" + scatterPortB)
                .servicePaths(java.util.List.of("/api"))
                .timeoutMillis(3000)
                // 将 node-B 的纯 HTTP 后端注册为集群服务
                .addServer("/api", "127.0.0.1", backendBPort, "http")
                .build();
        nodeA.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (nodeA != null) { try { nodeA.close(); } catch (Exception ignored) {} }
        if (nodeB != null) { try { nodeB.close(); } catch (Exception ignored) {} }
        if (backendB != null) { try { backendB.stop(0); } catch (Exception ignored) {} }
    }

    /**
     * 核心测试：curl node-A HTTP 入口 /api/hello → 应转发到 node-B 后端
     */
    @Test
    void testHttpForwardNodeAToNodeB() throws Exception {
        TimeUnit.SECONDS.sleep(3);

        // 验证：node-A 的服务表中包含注册的 backend
        java.util.Set<Discovery> services =
                nodeA.manager().nodes("/api", "cluster-forward", "http");
        System.err.println("[DEBUG] node-A /api services: " +
                services.stream().map(d -> d.getServerId() + "@" + d.getHost() + ":" + d.getPort()).toList());

        boolean hasBackend = services.stream().anyMatch(d -> backendBPort == d.getPort());
        Assertions.assertTrue(hasBackend,
                "node-A 服务表应包含后端服务（port=" + backendBPort + "），实际: " + services);

        // 先验证后端本身可达（排除后端自身问题）
        String directUrl = "http://127.0.0.1:" + backendBPort + "/api/hello";
        HttpURLConnection direct = (HttpURLConnection) new URL(directUrl).openConnection();
        direct.setRequestMethod("GET");
        direct.setConnectTimeout(3000);
        Assertions.assertEquals(200, direct.getResponseCode(), "后端本身应可访问: " + directUrl);
        direct.disconnect();

        // 正式测试：curl node-A HTTP 端口
        int nodeAHttpPort = nodeA.getHttpPort();
        String urlStr = "http://127.0.0.1:" + nodeAHttpPort + "/api/hello";
        System.err.println("[DEBUG] curl: " + urlStr + "  (backend=" + backendBPort + ")");

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        int status = conn.getResponseCode();
        String body;
        try (java.io.InputStream is = conn.getInputStream()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        Assertions.assertEquals(200, status,
                "curl " + urlStr + " 应返回 HTTP 200（转发到 node-B 后端）");
        Assertions.assertEquals("Hello from node-B", body,
                "响应内容应来自 node-B 后端，实际: " + body);
    }
}
