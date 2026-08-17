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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * onXxx 注解功能测试：验证 @OnOpen / @OnMessage / @OnClose / @OnError 四注解分发。
 *
 * <p>客户端注册带注解的 Bean，通过连接建立、消息收发、断开、异常四条路径
 * 分别触发对应注解方法，断言计数递增。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class OnXxxAnnotationTest {

    private static final String CLIENT_ID = "onnx-annotation-client";

    private KcpServer server;
    private KcpClient client;
    private int port;

    /**
     * 四注解处理器：每条路径触发对应方法并计数。
     */
    static class AnnotatedHandler {
        final AtomicInteger openCount = new AtomicInteger();
        final AtomicInteger messageCount = new AtomicInteger();
        final AtomicInteger closeCount = new AtomicInteger();
        final AtomicInteger errorCount = new AtomicInteger();
        final CountDownLatch messageLatch = new CountDownLatch(1);
        final CountDownLatch errorLatch = new CountDownLatch(1);

        @OnOpen
        public void onOpen() {
            openCount.incrementAndGet();
        }

        @OnMessage("onnx/#")
        public void onMessage(String payload) {
            messageCount.incrementAndGet();
            messageLatch.countDown();
            // 主动抛异常触发 @OnError 路径
            if ("boom".equals(payload)) {
                throw new IllegalStateException("boom");
            }
        }

        @OnClose
        public void onClose() {
            closeCount.incrementAndGet();
        }

        @OnError
        public void onError(Throwable throwable) {
            errorCount.incrementAndGet();
            errorLatch.countDown();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        port = freePort();
        server = new KcpServer(ServerSetting.builder()
                .host("127.0.0.1").port(port).protocol("kcp").build());
        server.start();
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
    void onXxxAnnotationsFire() throws Exception {
        AnnotatedHandler handler = new AnnotatedHandler();
        client = new KcpClient(CLIENT_ID, "kcp://127.0.0.1:" + port);
        // @OnOpen 需在连接前注册
        client.registerBean(handler);
        client.connect();

        // @OnOpen：连接建立时触发
        Thread.sleep(500);
        assertTrue(handler.openCount.get() >= 1, "@OnOpen 应在连接建立时触发");

        // @OnMessage + @OnError：服务端下行正常消息触发 onMessage，异常消息触发 onError
        // （客户端 @OnMessage 由服务端 send 下行触发，客户端自身 publish 不会回环到自己）
        server.send(CLIENT_ID, "onnx/greet", "hello");
        assertTrue(handler.messageLatch.await(3, TimeUnit.SECONDS), "@OnMessage 应在收到消息时触发");
        assertTrue(handler.messageCount.get() >= 1, "@OnMessage 计数应 >= 1");

        server.send(CLIENT_ID, "onnx/greet", "boom");
        assertTrue(handler.errorLatch.await(3, TimeUnit.SECONDS), "@OnError 应在注解方法抛异常时触发");
        assertTrue(handler.errorCount.get() >= 1, "@OnError 计数应 >= 1");

        // @OnClose：断开连接时触发
        client.close();
        Thread.sleep(500);
        assertTrue(handler.closeCount.get() >= 1, "@OnClose 应在断开连接时触发");
        client = null;
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
