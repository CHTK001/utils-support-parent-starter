package com.chua.apollo.support.spi;

// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。 // [P3C E1 豁免] // [P3C FM-字段注释 豁免]
import com.chua.datasource.support.middleware.MiddlewareClientProvider;
import com.chua.datasource.support.middleware.MiddlewareConnectRequest;
import com.chua.datasource.support.middleware.MiddlewareDocument;
import com.chua.datasource.support.middleware.MiddlewareQueryResult;
import com.chua.datasource.support.middleware.MiddlewareTestResult;
import com.chua.datasource.support.middleware.MiddlewareTreeNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适配器功能自测：使用 JVM 内置的简易 HTTP 服务器模拟 Apollo Admin/Config 服务，
 * 验证管理面查询链路。不依赖任何外部 Apollo 服务。
 *
 * @author CH
 * @since 2026-11-09
 */
class ApolloClientProviderMockTest {

    private static class MockServer implements Runnable {
        static final java.util.concurrent.atomic.AtomicInteger ACCEPTED = new java.util.concurrent.atomic.AtomicInteger();
        static final java.util.concurrent.atomic.AtomicInteger REJECTED = new java.util.concurrent.atomic.AtomicInteger();
        private final ServerSocket socket;
        private Thread thread;
        private volatile boolean running = true;

        MockServer(ServerSocket socket) {
            this.socket = socket;
        }

        void start() {
            // [P3C C1 豁免] 测试代码，约定线程名
            thread = new Thread(this, "apollo-mock");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            try {
                socket.close();
            } catch (Exception ignore) {
            }
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                while (running && !thread.isInterrupted()) {
                    Socket sock = socket.accept();
                    try {
                        handle(sock);
                    } catch (Exception e) {
                        REJECTED.incrementAndGet();
                        log("handle failed: " + e);
                    }
                }
            } catch (Exception ignore) {
                if (running) {
                    throw new RuntimeException("mock server failed", ignore);
                }
            }
        }

        private void handle(Socket sock) throws IOException {
            BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));
            PrintWriter w = new PrintWriter(sock.getOutputStream(), true);
            // 处理 HTTP/1.1 keep-alive 连接上的所有（流水线化）请求，直到客户端关闭连接，
            // 否则后续同一次连接发出的请求只能被 accept() 等待 —— 它们不在 backlog 里
            while (true) {
                StringBuilder req = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null && !line.isEmpty()) {
                    req.append(line).append("\n");
                }
                if (line == null) {
                    // 客户端关闭了连接
                    break;
                }
                String head = req.toString().split("\n")[0];
                String path = head.split(" ")[1];
                log("REQ " + path);
                String body = resolve(path);
                w.print("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                        + "Content-Length: " + body.length() + "\r\n\r\n" + body);
                w.flush();
                ACCEPTED.incrementAndGet();
            }
        }

        private void log(String s) { // [P3C MJ-方法注释 豁免]
            System.out.println("[MOCK] " + s);
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get("C:/temp/res.txt"),
                        (s + "\n").getBytes("UTF-8"),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                // [P3C E1 豁免] 测试日志，不阻塞正?流程
                System.out.println("[MOCK] log failed: " + e);
            }
        }

        private String resolve(String path) { // [P3C MJ-方法注释 豁免]
            log("RESOLVE path=" + path);
            if (path.startsWith("/v1/apps/10001/clusters/")) {
                if (path.endsWith("/namespaces")) {
                    // 命名空间列表（只有一个 default 集群，master 下无命名空间）
                    String ns = path.startsWith("/v1/apps/10001/clusters/master/namespaces") ? "[]"
                            : "[{\"namespaceName\":\"application\",\"appId\":\"10001\",\"clusterName\":\"default\","
                              + "\"format\":\"properties\",\"env\":\"DEV\",\"status\":\"default\"}]";
                    return "{\"code\":0,\"data\":" + ns + "}";
                }
                if (path.contains("/namespaces/")) {
                    return "{\"code\":0,\"data\":[{\"namespaceName\":\"application\",\"appId\":\"10001\","
                            + "\"clusterName\":\"default\",\"format\":\"properties\",\"env\":\"DEV\",\"status\":\"default\"}]}";
                }
                return "{\"code\":0,\"data\":[]}";
            }
            if (path.startsWith("/configs/")) {
                return "{\"appId\":\"10001\",\"cluster\":\"default\",\"namespaceName\":\"application\","
                        + "\"releaseKey\":\"1\",\"configurations\":{\"a\":\"1\",\"b\":\"2\"}}";
            }
            if (path.startsWith("/services/config")) {
                return "{\"code\":0,\"data\":[]}";
            }
            if (path.startsWith("/v1/apps")) {
                return "{\"code\":0,\"data\":[{\"appId\":\"10001\",\"name\":\"DemoApp\"}]}";
            }
            return "{}";
        }
    }

    private MockServer server;
    private ServerSocket socket;
    private int port;
    // [P3C FM-字段注释 豁免] 测试日志，运行时写入
    private final java.nio.file.Path log = java.nio.file.Paths.get("C:/temp/mock.log");
    private final java.nio.file.Path res = java.nio.file.Paths.get("C:/temp/res.txt");

    @AfterEach
    void cleanup() {
        if (server != null) {
            server.stop();
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignore) {
        }
    }

    @BeforeEach
    void startServer() throws Exception {
        try {
            java.nio.file.Files.delete(log);
        } catch (Exception ignore) {
        }
        socket = new ServerSocket(0);
        port = socket.getLocalPort();
        server = new MockServer(socket);
        server.start();
        // [P3C A20 豁免] 等待 mock 服务启动，调试用
        Thread.sleep(50);
    }

    private MiddlewareClientProvider provider() {
        return new ApolloClientProvider();
    }

    private MiddlewareConnectRequest req() {
        return MiddlewareConnectRequest.of("127.0.0.1", port, "", "", "");
    }

    @Test
    @DisplayName("模拟 Apollo 上跑通 连通测试 -> 应用列表 -> 命名空间 -> 配置项 整条链路")
    void fullChainWorks() {
        log("T1 CPN=" + System.getProperty("java.class.path").split(java.io.File.pathSeparator).length);
        try {
            MiddlewareClientProvider p = provider();
            MiddlewareConnectRequest r = req();

            MiddlewareTestResult t = p.test(r);
            log("T1 test=" + t);
            assertTrue(t.connected(), "连通测试应返回 ok: " + t);
            // 注意：ok() 的消息放在 detail 上，message 应为空 (与 Nacos 参考实现一致)
            assertTrue(t.detail().length() > 0, "detail not empty: " + t);

            List<MiddlewareTreeNode> top = p.listTopNodes(r);
            assertEquals(1, top.size());
            assertEquals("10001", top.get(0).key());
            assertEquals("DemoApp", top.get(0).label());
            assertEquals("appId=10001", top.get(0).comment());
            assertNull(top.get(0).estimatedCount());

            List<MiddlewareTreeNode> children = p.listChildren(r, top.get(0).key());
            assertEquals(1, children.size());
            assertEquals("10001|default|application", children.get(0).key());
            assertEquals("application", children.get(0).label());
            assertEquals("cluster=default appId=10001", children.get(0).comment());

            MiddlewareQueryResult apps = p.query(r, "apps", 10); // [P3C A20 豁免]
            assertEquals(1, apps.rowCount());
            assertEquals("appId|name", String.join("|", apps.columns()));
            assertEquals("10001", apps.rows().get(0).get("appId"));
            assertEquals("DemoApp", apps.rows().get(0).get("name"));

            MiddlewareQueryResult nss = p.query(r, "namespaces", 10); // [P3C A20 豁免]
            assertEquals(1, nss.rowCount());
            assertEquals("application", nss.rows().get(0).get("namespace"));
            assertEquals("default", nss.rows().get(0).get("cluster"));

            MiddlewareQueryResult cfg = p.query(r, "configs 10001|default|application", 10); // [P3C A20 豁免]
            assertEquals(2, cfg.rowCount());
            assertEquals(2, cfg.totalRows());
            assertEquals("a", cfg.rows().get(0).get("key"));
            assertEquals("1", cfg.rows().get(0).get("value"));
            assertEquals("b", cfg.rows().get(1).get("key"));
            assertEquals("2", cfg.rows().get(1).get("value"));

            MiddlewareQueryResult bad = p.query(r, "flushall", 10); // [P3C A20 豁免]
            assertNotNull(bad);
            assertTrue(bad.rows().isEmpty());
            assertTrue(bad.message().length() > 0);

            assertNotNull(p.document(r, "config", "10001|default|application"));
            assertNotNull(p.document(r, "namespace", "10001|default|application"));
            log("T1 PASS accepted=" + MockServer.ACCEPTED.get() + " rejected=" + MockServer.REJECTED.get());
        } catch (Exception e) {
            log("T1 EX " + e);
            throw e;
        } finally {
            if (server != null) {
                server.stop();
            }
        }
    }

    @Test
    @DisplayName("指向未监听端口时测试返回失败对象")
    void deadPortReturnsFail() {
        log("T2 CPN=" + System.getProperty("java.class.path").split(java.io.File.pathSeparator).length);
        try {
            MiddlewareClientProvider p = provider();
            // 指向一个从未监听过的端口
            MiddlewareConnectRequest r = MiddlewareConnectRequest.of("127.0.0.1", 1, "", "", "");
            MiddlewareTestResult t = p.test(r);
            log("T2 test=" + t);
            assertFalse(t.connected());
            assertTrue(t.message().length() > 0, "msg: " + t.message());

            // 不支持的命令：返回失败结果而不是抛异常
            // [P3C A20 豁免] 查询参数，调试用
            MiddlewareQueryResult q = p.query(r, "flushall", 10);
            assertNotNull(q);
            assertTrue(q.rows().isEmpty());
            assertTrue(q.message().length() > 0, "msg: " + q.message());
            log("T2 PASS");
        } catch (Exception e) {
            log("T2 EX " + e);
            throw e;
        }
    }

    private void log(String s) { // [P3C MJ-方法注释 豁免]
        try {
            java.nio.file.Files.write(res, (s + "\n").getBytes("UTF-8"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            // [P3C E1 豁免] 测试日志，不阻塞正常流程
            System.out.println("log failed: " + e);
        }
    }
}
