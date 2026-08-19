package com.chua.gateway.server.ws;

import com.chua.gateway.server.bridge.NoVncBridge;
import com.chua.gateway.server.server.GatewayServerBootstrap;
import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.store.InMemoryConnectionStore;
import com.chua.gateway.server.tunnel.GatewayTunnel;
import com.chua.gateway.server.tunnel.TunnelRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebSocket 桥接集成测试。
 *
 * <p>在 surefire fork JVM 内启动 gateway server + GatewayWsServer，
 * 用 JDK 内置 {@link HttpClient.WebSocketBuilder} 真实建立 WS 连接，
 * 验证握手 + bind 消息 + binary 帧透传到 mock VNC server。</p>
 *
 * <p>Mock VNC server 用 {@link java.net.ServerSocket} 接受 TCP，
 * 回写固定 frame（模拟 VNC server→client 帧）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class WsEndpointIntegrationTest {

    /**
     * 测试用 WS 端口基础值（避让 8182 默认）
     */
    private static final int WS_PORT_BASE = 9182;

    /**
     * 实际绑定 WS 端口
     */
    private static int wsPort;

    /**
     * 实际绑定 HTTP 端口
     */
    private static int httpPort;

    /**
     * Gateway server 实例
     */
    private static GatewayServerBootstrap gateway;

    /**
     * Mock VNC TCP server（接受 ws 转发过来的字节）
     */
    private static MockVncServer mockVnc;

    /**
     * 每个测试用的 tunnelId（保证互不干扰）
     */
    private String tunnelId;

    /**
     * 每个测试用的 bridge（连接 mock VNC server）
     */
    private NoVncBridge bridge;

    /**
     * 每个测试的 mock VNC 实际端口（BeforeAll 探测）
     */
    private static int mockPort;

    @BeforeAll
    static void startServers() throws Exception {
        // 0. 启动 mock VNC server（占 15900，循环回写）—— 加端口探测避开残留
        mockPort = findFreePort(15900, 15999);
        mockVnc = new MockVncServer(mockPort);
        mockVnc.start();

        // 1. 启动 gateway server
        //    端口探测 + 启动 GatewayWsServer
        int wsCandidate = WS_PORT_BASE;
        int httpCandidate = 8181;
        for (int i = 0; i < 32; i++) {
            int candidateWs = wsCandidate + i;
            int candidateHttp = httpCandidate + i;
            System.setProperty("gateway.ws.port", String.valueOf(candidateWs));
            System.setProperty("gateway.http.port", String.valueOf(candidateHttp));
            try {
                gateway = new GatewayServerBootstrap(
                        new InMemoryConnectionStore(),
                        new TunnelRegistry(),
                        new com.chua.gateway.server.api.ProtocolScanner());
                gateway.start();
                wsPort = gateway.wsBindingPort();
                httpPort = gateway.bindingPort();
                break;
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof java.net.BindException) {
                    try {
                        gateway.stop();
                    } catch (Exception ignored) {
                    }
                    continue;
                }
                throw ex;
            }
        }
        if (wsPort == 0) {
            throw new IllegalStateException("无法绑定 WS/HTTP 端口");
        }

        // 等待 server ready
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 5000) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", wsPort), 200);
                return;
            } catch (Exception e) {
                Thread.sleep(50);
            }
        }
    }

    @AfterAll
    static void stopServers() {
        if (gateway != null) {
            gateway.stop();
        }
        if (mockVnc != null) {
            mockVnc.stop();
        }
    }

    /**
     * 每个测试前注入独立的 tunnel + bridge，确保互不干扰。
     * 使用 UUID 后缀保证 tunnelId 唯一。
     */
    @org.junit.jupiter.api.BeforeEach
    void injectTunnel() throws Exception {
        this.tunnelId = "test-tunnel-" + java.util.UUID.randomUUID();
        Connection conn = new Connection("vnc", "127.0.0.1", mockPort, null, null, "test-key");
        this.bridge = new NoVncBridge(conn);
        this.bridge.connect();
        GatewayTunnel tunnel = GatewayTunnel.of(tunnelId, conn, bridge);
        java.lang.reflect.Field f = TunnelRegistry.class.getDeclaredField("tunnels");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, GatewayTunnel> map = (java.util.Map<String, GatewayTunnel>) f.get(gateway.tunnelRegistry());
        map.put(tunnelId, tunnel);
    }

    /**
     * 每个测试后清理上一个测试的 tunnel。
     */
    @org.junit.jupiter.api.AfterEach
    void cleanupTunnel() throws Exception {
        if (tunnelId != null) {
            java.lang.reflect.Field f = TunnelRegistry.class.getDeclaredField("tunnels");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, GatewayTunnel> map = (java.util.Map<String, GatewayTunnel>) f.get(gateway.tunnelRegistry());
            map.remove(tunnelId);
        }
        if (bridge != null) {
            try {
                bridge.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 在 [from, to] 区间探测一个空闲端口。
     */
    private static int findFreePort(int from, int to) {
        for (int p = from; p <= to; p++) {
            try (java.net.ServerSocket s = new java.net.ServerSocket(p)) {
                return p;
            } catch (Exception ignored) {
            }
        }
        throw new IllegalStateException("no free port in [" + from + "," + to + "]");
    }

    @Test
    void shouldCompleteHandshakeAndBind() throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + wsPort + "/ws/vnc/" + tunnelId);
        HttpClient client = HttpClient.newHttpClient();
        List<String> textMessages = new CopyOnWriteArrayList<>();
        List<ByteBuffer> binaryMessages = new CopyOnWriteArrayList<>();
        CompletableFuture<WebSocket> connectedFuture = new CompletableFuture<>();

        WebSocket ws = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(uri, new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        connectedFuture.complete(webSocket);
                        WebSocket.Listener.super.onOpen(webSocket);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        textMessages.add(data.toString());
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }

                    @Override
                    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                        binaryMessages.add(data);
                        return WebSocket.Listener.super.onBinary(webSocket, data, last);
                    }
                })
                .get(3, TimeUnit.SECONDS);

        WebSocket connected = connectedFuture.get(2, TimeUnit.SECONDS);

        // 发送 bind 消息
        connected.sendText("{\"action\":\"bind\",\"tunnelId\":\"" + tunnelId + "\"}", true);

        // 等服务端回 bound + 后续 binary echo
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && textMessages.isEmpty()) {
            Thread.sleep(50);
        }
        assertFalse(textMessages.isEmpty(), "应收到 bound 消息");
        assertTrue(textMessages.get(0).contains("\"bound\""), "首条消息应为 bound: " + textMessages.get(0));

        // 发 binary 帧（mock 客户端→VNC）—— VNC server 收到后回 echo
        ByteBuffer clientFrame = ByteBuffer.wrap(new byte[]{0x01, 0x02, 0x03, 0x04, (byte) 0xAB});
        connected.sendBinary(clientFrame, true);

        // 等服务端转发到 mock VNC + mock VNC 回 echo + 服务端转发回 client
        deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline && binaryMessages.isEmpty()) {
            Thread.sleep(100);
        }

        connected.sendClose(WebSocket.NORMAL_CLOSURE, "bye");

        // 完整链路断言：binary echo 应包含原 byte
        assertEquals(1, binaryMessages.size(), "应收到 1 条 binary echo");
        ByteBuffer echo = binaryMessages.get(0);
        byte[] echoBytes = new byte[echo.remaining()];
        echo.get(echoBytes);
        assertEquals(5, echoBytes.length, "echo 应保留原 5 字节");
        assertEquals((byte) 0xAB, echoBytes[4], "echo byte[4] 应保留 0xAB");
    }

    /**
     * 多次 binary 帧透传：验证 pump 线程持续运转 + 流式 echo。
     */
    @Test
    void shouldHandleMultipleBinaryFrames() throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + wsPort + "/ws/vnc/" + tunnelId);
        HttpClient client = HttpClient.newHttpClient();
        List<ByteBuffer> binaryMessages = new CopyOnWriteArrayList<>();
        CompletableFuture<WebSocket> connectedFuture = new CompletableFuture<>();

        WebSocket ws = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(uri, new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        connectedFuture.complete(webSocket);
                        WebSocket.Listener.super.onOpen(webSocket);
                    }

                    @Override
                    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                        binaryMessages.add(data);
                        return WebSocket.Listener.super.onBinary(webSocket, data, last);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }
                })
                .get(3, TimeUnit.SECONDS);
        WebSocket connected = connectedFuture.get(2, TimeUnit.SECONDS);
        connected.sendText("{\"action\":\"bind\",\"tunnelId\":\"" + tunnelId + "\"}", true);

        // 等服务端就绪（bound 帧）
        Thread.sleep(500);

        // 发 3 帧不同 payload
        for (int i = 0; i < 3; i++) {
            byte[] payload = new byte[]{(byte) (i + 1), (byte) (i + 2), (byte) (i + 3)};
            connected.sendBinary(ByteBuffer.wrap(payload), true);
            Thread.sleep(100);
        }

        // 等所有 echo
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline && binaryMessages.size() < 3) {
            Thread.sleep(100);
        }
        connected.sendClose(WebSocket.NORMAL_CLOSURE, "bye");

        assertTrue(binaryMessages.size() >= 3, "应收到至少 3 条 echo，实际: " + binaryMessages.size());
    }

    /**
     * Mock VNC server：接受任意连接，收到字节后 echo 回去。
     */
    static final class MockVncServer {
        /** 端口 */
        private final int port;
        /** 服务器Socket */
        private java.net.ServerSocket serverSocket;
        /** running */
        private volatile boolean running = true;
        /** Clients */
        private final List<java.net.Socket> clients = new CopyOnWriteArrayList<>();
        /** Accept线程 */
        private Thread acceptThread;

        MockVncServer(int port) {
            this.port = port;
        }

        void start() throws java.io.IOException {
            serverSocket = new java.net.ServerSocket(port);
            acceptThread = new Thread(this::acceptLoop, "mock-vnc");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        void stop() {
            running = false;
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (Exception ignored) {
            }
            for (java.net.Socket s : clients) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }

        private void acceptLoop() {
            while (running && serverSocket != null && !serverSocket.isClosed()) {
                try {
                    java.net.Socket client = serverSocket.accept();
                    clients.add(client);
                    Thread t = new Thread(() -> serveClient(client), "mock-vnc-serve");
                    t.setDaemon(true);
                    t.start();
                } catch (java.io.IOException e) {
                    if (running) {
                        // ignore
                    }
                }
            }
        }

        private void serveClient(java.net.Socket client) {
            try {
                java.io.InputStream in = client.getInputStream();
                java.io.OutputStream out = client.getOutputStream();
                byte[] buf = new byte[4096];
                while (running && !client.isClosed()) {
                    int n = in.read(buf);
                    if (n <= 0) {
                        break;
                    }
                    // echo back
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (Exception e) {
                // ignore
            } finally {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
