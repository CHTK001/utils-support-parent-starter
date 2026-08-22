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
import java.util.concurrent.TimeUnit;

/**
 * ClusterServer 端到端 HTTP 转发测试。
 *
 * <p>验证：curl 请求 Node A 的 HTTP 入口 → ServiceDiscoveryServerFilter 选目标 →
 * ReverseProxyServerFilter 代理转发 → 拿到后端响应。</p>
 */
public class ClusterServerForwardTest {

    /** node-B 的纯 HTTP 后端（不经 ClusterServer） */
    private HttpServer backendB;
    private int backendBPort;

    private ClusterServer nodeA;
    private ClusterServer nodeB;

    @BeforeEach
    void setUp() throws Exception {
        ScatterSyncHelper.resetForTest();
        // 清理上一轮测试的持久化文件，防止跨测试污染
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(".scatter-nodes-node-a.json"));
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(".scatter-nodes-node-b.json"));

        // ── ① 启动 node-B 的纯 HTTP 后端，监听 /api/hello（与注册的服务路径对齐）
        backendB = HttpServer.create(new InetSocketAddress(0), 0);
        backendB.createContext("/api/hello", exchange -> {
            byte[] body = "Hello from node-B".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        backendB.setExecutor(null);
        backendB.start();
        backendBPort = backendB.getAddress().getPort();

        // ── ② 启动 node-B（seed 网关）—— 只作为 scatter seed，不注册后端服务
        nodeB = ClusterServer.builder()
                .nodeId("node-b").host("127.0.0.1").port(0)
                .scatterId("forward-test")
                .servicePaths(java.util.List.of("/api"))
                .timeoutMillis(3000)
                .build();
        nodeB.start();

        // ── ③ 启动 node-A：注册后端服务到集群
        //    addServer("/api", ...) 使 backendB 以 servicePath=/api 注册进 scatter
        //    请求 /api/hello → ServiceDiscoveryServerFilter 匹配 /api/** → 路由到 /api
        //    → ReverseProxyServerFilter 转发到 127.0.0.1:backendBPort/api/hello
        nodeA = ClusterServer.builder()
                .nodeId("node-a").host("127.0.0.1").port(0)
                .scatterId("forward-test")
                .seeds("127.0.0.1:" + nodeB.getScatterPort())
                .servicePaths(java.util.List.of("/api"))
                .timeoutMillis(3000)
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
     * 核心测试：curl http://nodeA-http-port/api/hello → 转发到 backendB → "Hello from node-B"
     */
    @Test
    void testHttpForwardNodeAToNodeB() throws Exception {
        // 等待 scatter 同步完成
        TimeUnit.SECONDS.sleep(3);

        // 验证：node-A 的服务表中包含 backendB
        java.util.Set<Discovery> services =
                nodeA.manager().nodes("/api", "forward-test", "http");
        boolean hasBackend = services.stream().anyMatch(d -> backendBPort == d.getPort());
        Assertions.assertTrue(hasBackend,
                "node-A 服务表应包含 backendB（port=" + backendBPort + "）: " + services);

        // 先验证后端本身可达
        String directUrl = "http://127.0.0.1:" + backendBPort + "/api/hello";
        HttpURLConnection direct = (HttpURLConnection) new URL(directUrl).openConnection();
        direct.setRequestMethod("GET");
        direct.setConnectTimeout(3000);
        int directStatus = direct.getResponseCode();
        Assertions.assertEquals(200, directStatus,
                "后端本身应可访问（port=" + backendBPort + "），实际: " + directStatus);
        String directBody = new String(direct.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assertions.assertEquals("Hello from node-B", directBody);
        direct.disconnect();

        // 正式测试：curl Node A 的 HTTP 端口
        int nodeAHttpPort = nodeA.getHttpPort();
        String urlStr = "http://127.0.0.1:" + nodeAHttpPort + "/api/hello";
        System.err.println("[E2E] curl " + urlStr + " → backend " + directUrl);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        int status = conn.getResponseCode();
        String body;
        try (java.io.InputStream is = conn.getInputStream()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            String errBody;
            try (java.io.InputStream is = conn.getErrorStream()) {
                errBody = is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "(no body)";
            }
            Assertions.fail("HTTP " + status + " from " + urlStr + ", body: " + errBody);
            return;
        }

        Assertions.assertEquals(200, status,
                "curl " + urlStr + " 应返回 HTTP 200（转发到 node-B 后端）");
        Assertions.assertEquals("Hello from node-B", body,
                "响应内容应来自 node-B 后端，实际: " + body);
    }

    /**
     * 辅助测试：验证 scatter 双向发现正常
     */
    @Test
    void testScatterDiscovery() throws Exception {
        TimeUnit.SECONDS.sleep(5);

        java.util.Set<Discovery> bServices = nodeB.discovery().getServiceAll("/api");
        boolean hasNodeA = bServices.stream().anyMatch(d -> "node-a".equals(d.getServerId()));
        Assertions.assertTrue(hasNodeA, "node-B 应通过 scatter 发现 node-A. Services: " + bServices);
    }
}
