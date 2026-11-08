package com.chua.apollo.support.spi;

import com.chua.datasource.support.middleware.MiddlewareClientProvider;
import com.chua.datasource.support.middleware.MiddlewareConnectRequest;
import com.chua.datasource.support.middleware.MiddlewareQueryResult;
import com.chua.datasource.support.middleware.MiddlewareTestResult;
import com.chua.datasource.support.middleware.MiddlewareTreeNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 适配器功能自测：使用 JVM 内置的简易 HTTP 服务器模拟 Apollo Admin/Config 服务，
 * 验证管理面查询链路。不依赖任何外部 Apollo 服务。
 */
class ApolloClientProviderMockTest {

    /** 模拟服务器：按路径返回 Apollo 管理面的标准 JSON 回答 */
    private static class MockServer implements Runnable {
        private final int port;
        private final ExecutorService pool = Executors.newFixedThreadPool(8);
        private volatile boolean running = true;

        MockServer(int port) {
            this.port = port;
        }

        @Override
        public void run() {
            ServerSocket ss;
            try {
                ss = new ServerSocket(port);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
            while (running) {
                try {
                    Socket sock = ss.accept();
                    pool.execute(() -> handle(sock));
                } catch (IOException ignore) {
                    break;
                }
            }
            try {
                ss.close();
            } catch (IOException ignore) {
            }
            pool.shutdown();
        }

        private void handle(Socket sock) {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));
                StringBuilder req = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null && !line.isEmpty()) {
                    req.append(line).append("\n");
                }
                StringBuilder resp = new StringBuilder();
                PrintWriter w = new PrintWriter(sock.getOutputStream(), true);
                String head = req.toString().split("\n")[0];
                String p = head.split(" ")[1];

                // 注意：/v1/apps/.. 是 /namespaces 的父路径，必须放在最后匹配
                if (p.contains("/services/config")) {
                    resp.append("[{\"appName\":\"demo\",\"homepageUrl\":\"http://x\"}]");
                } else if (p.contains("/configs/")) {
                    resp.append("{\"appId\":\"10001\",\"cluster\":\"default\",\"namespaceName\":\"application\",")
                            .append("\"releaseKey\":\"1\",\"configurations\":{\"a\":\"1\",\"b\":\"2\"}}");
                } else if (p.contains("/v1/apps") && p.contains("/namespaces")) {
                    resp.append("{\"code\":0,\"data\":[{\"namespaceName\":\"application\",\"format\":\"properties\"}]}");
                } else if (p.contains("/v1/apps")) {
                    resp.append("{\"code\":0,\"data\":[{\"appId\":\"10001\",\"name\":\"DemoApp\"}]}");
                } else {
                    resp.append("{}");
                }

                w.print("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                        + "Content-Length: " + resp.length() + "\r\n\r\n" + resp);
                w.flush();
                sock.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        void shutdown() {
            running = false;
            pool.shutdownNow();
        }
    }

    private MiddlewareClientProvider provider() {
        return new ApolloClientProvider();
    }

    private MiddlewareConnectRequest req(int port) {
        return MiddlewareConnectRequest.of("127.0.0.1", port, "", "", "");
    }

    @Test
    @DisplayName("模拟 Apollo 上跑通 连通测试 -> 应用列表 -> 命名空间 -> 配置项 整条链路")
    void fullChainWorks() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            MockServer mock = new MockServer(port);
            Thread server = new Thread(mock);
            server.setDaemon(true);
            server.start();
            TimeUnit.MILLISECONDS.sleep(300); // server started

            try {
                MiddlewareClientProvider p = provider();
                MiddlewareConnectRequest r = req(port);

                // 1. 连接测试（管理面 /v1/apps 返回 data 列表即成功）
                MiddlewareTestResult t = p.test(r);
                assertTrue(t.connected(), "连通测试应返回 ok: " + t);
                assertFalse(t.message().isBlank());

                // 2. 顶层节点 = 应用
                List<MiddlewareTreeNode> top = p.listTopNodes(r);
                assertNotNull(top);
                assertEquals(1, top.size());
                assertEquals("10001", top.get(0).key());
                assertEquals("DemoApp", top.get(0).label());
                assertEquals("appId=10001", top.get(0).comment());
                assertNull(top.get(0).estimatedCount());

                // 3. 子节点 = 命名空间
                List<MiddlewareTreeNode> children = p.listChildren(r, top.get(0).key());
                assertNotNull(children);
                assertEquals(1, children.size());
                assertEquals("10001|default|application", children.get(0).key());
                assertEquals("application", children.get(0).label());
                assertEquals("cluster=default appId=10001", children.get(0).comment());

                // 4. queries
                MiddlewareQueryResult apps = p.query(r, "apps", 10);
                assertEquals(1, apps.rowCount());
                assertEquals("appId|name", String.join("|", apps.columns()));
                assertEquals("10001", apps.rows().get(0).get("appId"));
                assertEquals("DemoApp", apps.rows().get(0).get("name"));

                MiddlewareQueryResult nss = p.query(r, "namespaces", 10);
                assertEquals(1, nss.rowCount());
                assertEquals("application", nss.rows().get(0).get("namespace"));
                assertEquals("default", nss.rows().get(0).get("cluster"));

                MiddlewareQueryResult cfg = p.query(r, "configs 10001|default|application", 10);
                assertEquals(2, cfg.rowCount());
                assertEquals(2, cfg.totalRows());
                assertEquals("a", cfg.rows().get(0).get("key"));
                assertEquals("1", cfg.rows().get(0).get("value"));
                assertEquals("b", cfg.rows().get(1).get("key"));
                assertEquals("2", cfg.rows().get(1).get("value"));

                // 5. 不支持的查询返回失败对象，不抛异常
                MiddlewareQueryResult bad = p.query(r, "flushall", 10);
                assertNotNull(bad);
                assertTrue(bad.rows().isEmpty());
                assertFalse(bad.message().isBlank());

                // 6. 详情
                assertNotNull(p.document(r, "config", "10001|default|application"));
                assertNotNull(p.document(r, "namespace", "10001|default|application"));
            } finally {
                mock.shutdown();
            }
        }
    }

    @Test
    @DisplayName("意外中断的模拟服务器下测试返回失败对象")
    void unexpectedErrorReturnsFail() {
        try {
            MiddlewareClientProvider p = provider();
            // 指向一个从未监听过的端口
            MiddlewareConnectRequest r = req(1);
            MiddlewareTestResult t = p.test(r);
            assertFalse(t.connected());
            assertFalse(t.message().isBlank());

            // 服务不存在时查询也应返回失败结果，不抛异常
            MiddlewareQueryResult q = p.query(r, "apps", 10);
            assertNotNull(q);
            assertTrue(q.rows().isEmpty());
            assertFalse(q.message().isBlank());
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("provider 不应向调用方抛出: " + e, e);
        }
    }
}
