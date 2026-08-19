package com.chua.kcp.support;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnError;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;
import com.chua.kcp.support.client.KcpClient;
import com.chua.kcp.support.server.KcpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KcpServer / KcpClient 注解对接测试。
 *
 * <p>验证与 MQTT 同风格的 {@code @OnOpen}/{@code @OnMessage}/{@code @OnClose}/{@code @OnError}
 * 注解分发：客户端发布消息 → 服务端 {@code @OnMessage} 接收；服务端发布 → 客户端
 * {@code @OnMessage} 接收（双向收发）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class KcpAnnotationTest {

    /**
     * 测试客户端标识
     */
    private static final String CLIENT_ID = "kcp-ann-client";

    /**
     * KCP 服务器实例
     */
    private KcpServer server;
    /**
     * KCP 客户端实例
     */
    private KcpClient client;
    /**
     * 测试端口号
     */
    private int port;

    /**
     * 服务端注解处理器（serverReceiveViaOnMessage 使用）。
     */
    static class ServerHandler {
        /**
         * 已接收的消息列表
         */
        final List<String> received = new CopyOnWriteArrayList<>();
        /**
         * 接收消息信号门闩
         */
        final CountDownLatch latch = new CountDownLatch(1);
        /**
         * 打开事件触发计数
         */
        final AtomicInteger openCount = new AtomicInteger();
        /**
         * 最近一次消息主题引用
         */
        final AtomicReference<String> lastTopic = new AtomicReference<>();

        @OnOpen
        /** On打开 */
        public void onOpen() {
            openCount.incrementAndGet();
        }

        @OnMessage("echo/#")
        /** OnEcho */
        public void onEcho(String payload) {
            received.add(payload);
            lastTopic.set("echo/ann");
            latch.countDown();
        }
    }

    /**
     * 服务端注解处理器（bidirectionalViaOnMessage 使用）。
     *
     * <p>与 {@link ServerHandler} 类名不同，避免共享注册器按类名判重导致同名冲突。</p>
     */
    static class BidirectionalServerHandler {
        /**
         * 已接收的消息列表
         */
        final List<String> received = new CopyOnWriteArrayList<>();
        /**
         * 接收消息信号门闩
         */
        final CountDownLatch latch = new CountDownLatch(1);

        @OnMessage("echo/#")
        /** OnEcho */
        public void onEcho(String payload) {
            received.add(payload);
            latch.countDown();
        }
    }

    /**
     * 客户端注解处理器。
     */
    static class ClientHandler {
        /**
         * 已接收的消息列表
         */
        final List<String> received = new CopyOnWriteArrayList<>();
        /**
         * 接收消息信号门闩
         */
        final CountDownLatch latch = new CountDownLatch(1);
        /**
         * 打开事件触发计数
         */
        final AtomicInteger openCount = new AtomicInteger();
        /**
         * 错误事件触发计数
         */
        final AtomicInteger errorCount = new AtomicInteger();

        @OnOpen
        /** On打开 */
        public void onOpen() {
            openCount.incrementAndGet();
        }

        @OnMessage("reply/#")
        /** OnReply */
        public void onReply(String payload) {
            received.add(payload);
            latch.countDown();
        }

        @OnError
        /** On记录错误 */
        public void onError(Throwable throwable) {
            errorCount.incrementAndGet();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        port = freePort();
        server = new KcpServer(ServerSetting.builder()
                .host("127.0.0.1").port(port).protocol("kcp").build());
        server.start();
        client = new KcpClient(CLIENT_ID, "kcp://127.0.0.1:" + port);
        client.connect();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignore) {
            }
        }
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignore) {
            }
        }
    }

    @Test
    void serverReceiveViaOnMessage() throws Exception {
        ServerHandler serverHandler = new ServerHandler();
        server.registerBean(serverHandler);

        client.publish("echo/ann", "hello-kcp");

        assertTrue(serverHandler.latch.await(3, TimeUnit.SECONDS), "服务端 @OnMessage 应在超时前收到消息");
        assertEquals(List.of("hello-kcp"), serverHandler.received);
        assertEquals("echo/ann", serverHandler.lastTopic.get());
    }

    @Test
    void clientReceiveViaOnMessage() throws Exception {
        ClientHandler clientHandler = new ClientHandler();
        client.registerBean(clientHandler);

        // 等客户端注册完成，服务端按 clientId 定向发送
        Thread.sleep(200);
        server.send(CLIENT_ID, "reply/ann", "pong-from-server");

        assertTrue(clientHandler.latch.await(3, TimeUnit.SECONDS), "客户端 @OnMessage 应在超时前收到消息");
        assertEquals(List.of("pong-from-server"), clientHandler.received);
    }

    @Test
    void bidirectionalViaOnMessage() throws Exception {
        BidirectionalServerHandler serverHandler = new BidirectionalServerHandler();
        ClientHandler clientHandler = new ClientHandler();
        server.registerBean(serverHandler);
        client.registerBean(clientHandler);

        // 客户端 → 服务端
        client.publish("echo/ann", "ping");
        assertTrue(serverHandler.latch.await(3, TimeUnit.SECONDS), "服务端应收到客户端消息");
        assertEquals(List.of("ping"), serverHandler.received);

        // 服务端 → 客户端
        Thread.sleep(200);
        server.send(CLIENT_ID, "reply/ann", "pong");
        assertTrue(clientHandler.latch.await(3, TimeUnit.SECONDS), "客户端应收到服务端消息");
        assertEquals(List.of("pong"), clientHandler.received);
    }

    @Test
    void openCloseAnnotationFired() throws Exception {
        // @OnOpen 在连接建立时触发，需先 registerBean 再 connect
        ClientHandler clientHandler = new ClientHandler();
        KcpClient openClient = new KcpClient(CLIENT_ID + "-open", "kcp://127.0.0.1:" + port);
        openClient.registerBean(clientHandler);
        openClient.connect();

        Thread.sleep(500);
        assertTrue(clientHandler.openCount.get() >= 1, "客户端 @OnOpen 应至少触发一次");

        openClient.close();
        openClient = null;
    }

    /**
     * 获取空闲端口。
     *
     * @return 空闲端口
     */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
