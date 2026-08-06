package com.chua.runtime.e2e;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.handler.DependencyGraphHandler;
import com.chua.runtime.apm.handler.HandleLeakHandler;
import com.chua.runtime.apm.handler.TraceHandler;
import com.chua.runtime.apm.handler.TransmissionHandler;
import com.chua.runtime.protocol.TransmissionRecord;
import com.chua.runtime.spy.RuntimeSpy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真实网络传输 e2e — 用 ServerSocket/Socket 模拟 client/server，
 * 验证 TransmissionHandler / DependencyGraphHandler / TraceHandler 端到端联动。
 *
 * <p>注意：</p>
 * <ul>
 *   <li>Spy / Handler 在测试进程内启动（不走 javaagent），因此 Socket/ServerSocket
 *       的真实字节码插桩不会发生 —— 测试仅验证 Handler 实例注册 + 状态可读。</li>
 *   <li>真实 Socket 拦截需要 javaagent 启动（RuntimeAgent.premain）。这部分在
 *       {@link com.chua.runtime.agent.RuntimeAgent} 中覆盖。</li>
 * </ul>
 *
 * <p>测试范围：</p>
 * <ol>
 *   <li>ApmBootstrap 显式注册 TransmissionHandler/DependencyGraphHandler/HandleLeakHandler</li>
 *   <li>真实 ServerSocket/Socket 连接 + 数据收发成功</li>
 *   <li>Handler 实例可查询（name/version/isRunning）</li>
 *   <li>关闭 server 后 connect 抛 IOException</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TransmissionE2ETest {

    /**
     * 测试主机
     */
    private static final String HOST = "127.0.0.1";

    /**
     * 测试响应
     */
    private static final String RESPONSE = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK";

    private ServerSocket server;
    private ApmBootstrap apm;

    /**
     * 业务服务端 — 接收客户端请求并返回 OK。
     */
    private static final class ServerWorker extends Thread {
        private final Socket socket;

        ServerWorker(Socket socket) {
            this.socket = socket;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                byte[] buf = new byte[2048];
                int n = in.read(buf);
                if (n > 0) {
                    out.write(RESPONSE.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException ignore) {
            } finally {
                try {
                    socket.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    @Before
    public void setup() throws Exception {
        RuntimeSpy.clear();

        // 1. 构造 ApmBootstrap + 显式注册 3 个新 Handler
        apm = new ApmBootstrap(Paths.get(System.getProperty("java.io.tmpdir")));
        apm.addHandler(new TransmissionHandler());
        apm.addHandler(new DependencyGraphHandler());
        apm.addHandler(new HandleLeakHandler());
        apm.start();

        // 2. 启动 ServerSocket（0 端口随机分配）
        server = new ServerSocket(0);
    }

    @After
    public void teardown() throws Exception {
        if (server != null && !server.isClosed()) {
            server.close();
        }
        if (apm != null) {
            apm.stop();
        }
        RuntimeSpy.clear();
    }

    /**
     * 测试 1: 三个新 Handler 均注册成功。
     */
    @Test
    public void testHandlersRegistered() {
        TransmissionHandler transmission = apm.getHandler(TransmissionHandler.class);
        DependencyGraphHandler graph = apm.getHandler(DependencyGraphHandler.class);
        HandleLeakHandler leak = apm.getHandler(HandleLeakHandler.class);

        assertNotNull("TransmissionHandler 应注册", transmission);
        assertNotNull("DependencyGraphHandler 应注册", graph);
        assertNotNull("HandleLeakHandler 应注册", leak);

        assertEquals("transmission-handler", transmission.name());
        assertEquals("dependency-graph-handler", graph.name());
        assertEquals("handle-leak-handler", leak.name());

        assertTrue("TransmissionHandler 应运行中", transmission.isRunning());
        assertTrue("DependencyGraphHandler 应运行中", graph.isRunning());
        assertTrue("HandleLeakHandler 应运行中", leak.isRunning());
    }

    /**
     * 测试 2: 真实 ServerSocket 接受连接 + 数据收发。
     */
    @Test
    public void testServerSocketAcceptAndCommunicate() throws Exception {
        int port = server.getLocalPort();
        ServerWorker worker = new ServerWorker(startServerAccept());

        try (Socket client = new Socket(HOST, port)) {
            client.getOutputStream().write("GET /test HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            byte[] buf = new byte[256];
            int n = client.getInputStream().read(buf);
            assertTrue("应读到响应（实际 n=" + n + "）", n > 0);
            String response = new String(buf, 0, n, StandardCharsets.UTF_8);
            assertTrue("响应应包含 200 OK（实际: " + response + "）", response.contains("200 OK"));

            worker.join(5000);
            assertFalse("工作线程应已结束", worker.isAlive());
        }
    }

    /**
     * 测试 3: 关闭 server 后 connect 抛 IOException。
     */
    @Test
    public void testConnectAfterServerClosed() throws Exception {
        int port = server.getLocalPort();
        server.close();

        try (Socket client = new Socket()) {
            try {
                client.connect(new java.net.InetSocketAddress(HOST, port), 200);
                assertFalse("服务器关闭后 connect 不应成功", client.isConnected());
            } catch (IOException expected) {
                assertTrue("预期连接被拒绝", true);
            }
        }
    }

    /**
     * 测试 4: 多次并发连接均成功。
     */
    @Test
    public void testConcurrentConnections() throws Exception {
        int port = server.getLocalPort();
        int concurrent = 3;

        Thread[] workers = new Thread[concurrent];
        Socket[] holders = new Socket[concurrent];
        Thread[] acceptors = new Thread[concurrent];

        for (int i = 0; i < concurrent; i++) {
            final int idx = i;
            holders[i] = new Socket();
            acceptors[i] = new Thread(() -> {
                try {
                    holders[idx] = server.accept();
                } catch (IOException ignore) {
                }
            }, "test-accept-" + i);
            acceptors[i].setDaemon(true);
            acceptors[i].start();
        }

        for (int i = 0; i < concurrent; i++) {
            holders[i].connect(new java.net.InetSocketAddress(HOST, port), 2000);
            workers[i] = new ServerWorker(holders[i]);
            workers[i].start();
        }

        for (int i = 0; i < concurrent; i++) {
            Socket c = new Socket(HOST, port);
            c.getOutputStream().write("X".getBytes(StandardCharsets.UTF_8));
            c.getOutputStream().flush();
            byte[] buf = new byte[64];
            int n = c.getInputStream().read(buf);
            assertTrue("第 " + i + " 个连接应读到响应（实际 n=" + n + "）", n > 0);
            c.close();
        }

        for (Thread w : workers) {
            if (w != null) {
                w.join(5000);
            }
        }
    }

    /**
     * 测试 5: TransmissionHandler.getRecords() 在测试进程内返回空列表（无 javaagent 不触发）。
     */
    @Test
    public void testTransmissionRecordsEmptyWithoutAgent() {
        TransmissionHandler transmission = apm.getHandler(TransmissionHandler.class);
        assertNotNull(transmission);
        List<TransmissionRecord> records = transmission.getRecords();
        assertNotNull("records 不应为 null", records);
        assertTrue("无 javaagent 时 records 应为空", records.isEmpty());
    }

    /**
     * 测试 6: ApmBootstrap.status() 包含所有 Handler 名称。
     */
    @Test
    public void testApmStatusIncludesAllHandlers() {
        String status = apm.status();
        assertNotNull(status);
        assertTrue("status 应包含 transmission-handler: " + status,
                status.contains("transmission-handler"));
        assertTrue("status 应包含 dependency-graph-handler",
                status.contains("dependency-graph-handler"));
        assertTrue("status 应包含 handle-leak-handler",
                status.contains("handle-leak-handler"));
        assertTrue("status 应包含 log-handler", status.contains("log-handler"));
        assertTrue("status 应包含 trace-handler", status.contains("trace-handler"));
    }

    /**
     * 启动 server.accept 后台线程并返回已连接的 Socket。
     *
     * @return 客户端连接的 Socket
     */
    private Socket startServerAccept() throws InterruptedException {
        final Socket[] holder = new Socket[1];
        Thread acceptor = new Thread(() -> {
            try {
                holder[0] = server.accept();
            } catch (IOException ignore) {
            }
        }, "test-server-accept");
        acceptor.setDaemon(true);
        acceptor.start();

        long deadline = System.currentTimeMillis() + 2000L;
        while (holder[0] == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        if (holder[0] == null) {
            throw new IllegalStateException("server.accept 超时");
        }
        return holder[0];
    }

    /**
     * 断言相等 — 避免静态 import。
     *
     * @param expected 期望值
     * @param actual   实际值
     */
    private static void assertEquals(String expected, String actual) {
        org.junit.Assert.assertEquals(expected, actual);
    }
}
