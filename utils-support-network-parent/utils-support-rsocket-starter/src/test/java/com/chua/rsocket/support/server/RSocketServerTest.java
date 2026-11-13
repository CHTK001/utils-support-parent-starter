// [P3C A20 豁免] 测试断言与协议常量含魔法值场景，豁免。
package com.chua.rsocket.support.server;

import com.chua.common.support.network.server.ServerSetting;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.core.RSocketConnector;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.util.DefaultPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RSocketServer} 的真实链路回归（此前零用例）。
 *
 * <p>服务端基于 RSocket + Netty TCP transport，按 payload 里的 {@code topic} 选处理器。
 * 本测试用 <b>RSocket 官方客户端</b>（{@link RSocketConnector}）走真实 TCP，
 * 覆盖 requestResponse / fireAndForget / requestStream 三种交互模型。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class RSocketServerTest {

    /**
     * 取空闲端口。
     *
     * @return 端口
     * @throws Exception 套接字失败
     */
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    /**
     * 构造服务端（固定端口，便于客户端连接）。
     *
     * @param port 端口
     * @return 服务端
     */
    private static RSocketServer server(int port) {
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setAuto(false);
        return new RSocketServer(setting);
    }

    /**
     * 连接服务端。
     *
     * @param port 端口
     * @return 客户端 RSocket
     */
    private static RSocket connect(int port) {
        return RSocketConnector.create()
                .connect(TcpClientTransport.create("127.0.0.1", port))
                .block(Duration.ofSeconds(15));
    }

    /**
     * 构造带 topic 的请求 payload。
     *
     * @param topic 主题
     * @return payload
     */
    private static Payload payload(String topic) {
        return DefaultPayload.create("{\"topic\":\"" + topic + "\"}");
    }

    /**
     * requestResponse：处理器响应体必须回传给客户端。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(90)
    @DisplayName("RSocketServer：requestResponse 回传处理器结果")
    void requestResponseReturnsHandlerBody() throws Exception {
        int port = freePort();
        RSocketServer server = server(port);
        server.onRequest("echo", (request, response) -> {
            response.setBody("pong:" + request.getBodyString());
            response.end();
        });
        server.start();
        RSocket client = connect(port);
        try {
            assertNotNull(client, "客户端连接失败");
            Payload response = client.requestResponse(payload("echo")).block(Duration.ofSeconds(15));
            assertNotNull(response, "requestResponse 未返回响应");
            assertTrue(response.getDataUtf8().startsWith("pong:"),
                    "响应体不符: " + response.getDataUtf8());
        } finally {
            client.dispose();
            server.stop();
        }
    }

    /**
     * fireAndForget：请求不发响应，但处理器必须被调用。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(90)
    @DisplayName("RSocketServer：fireAndForget 触发处理器")
    void fireAndForgetInvokesHandler() throws Exception {
        int port = freePort();
        RSocketServer server = server(port);
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicReference<String> body = new AtomicReference<>();
        server.onRequest("faf", (request, response) -> {
            body.set(request.getBodyString());
            invoked.countDown();
        });
        server.start();
        RSocket client = connect(port);
        try {
            client.fireAndForget(payload("faf")).block(Duration.ofSeconds(15));
            assertTrue(invoked.await(10, TimeUnit.SECONDS), "fireAndForget 未触发处理器");
            assertNotNull(body.get(), "处理器未收到请求体");
        } finally {
            client.dispose();
            server.stop();
        }
    }

    /**
     * requestStream：客户端订阅主题后，服务端 publish 必须推送到流。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(90)
    @DisplayName("RSocketServer：requestStream 接收服务端推送")
    void requestStreamReceivesPublish() throws Exception {
        int port = freePort();
        RSocketServer server = server(port);
        server.start();
        RSocket client = connect(port);
        try {
            CountDownLatch got = new CountDownLatch(1);
            AtomicReference<String> received = new AtomicReference<>();
            reactor.core.Disposable subscription = client.requestStream(payload("stream"))
                    .subscribe(next -> {
                        received.set(next.getDataUtf8());
                        got.countDown();
                    });
            // 等服务端把 sink 注册进 topicSubscribers 后再 publish；
            // 轮询发布（最多 5s），排除订阅注册的时序竞争
            long deadline = System.currentTimeMillis() + 5_000L;
            while (got.getCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(200L);
                server.publish("stream", "hello-stream");
            }
            assertTrue(got.await(10, TimeUnit.SECONDS), "requestStream 未收到推送");
            assertEquals("hello-stream", received.get(), "推送内容不符");
            subscription.dispose();
        } finally {
            client.dispose();
            server.stop();
        }
    }
}
