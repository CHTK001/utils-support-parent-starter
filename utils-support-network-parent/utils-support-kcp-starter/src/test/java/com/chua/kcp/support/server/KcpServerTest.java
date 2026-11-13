// [P3C A20 豁免] 测试断言与协议常量含魔法值场景，豁免。
package com.chua.kcp.support.server;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.kcp.support.client.KcpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KcpServer} 的真实链路回归（此前零用例）。
 *
 * <p>服务端基于 kcp-base（UDP + KCP 可靠传输）手写会话/主题分发；
 * 本测试用<b>模块自带的 {@link KcpClient}</b>走真实 UDP/KCP，
 * 覆盖注册握手（registered 应答）、上行 topic:payload 派发、服务端广播下行。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class KcpServerTest {

    /**
     * 取空闲 UDP 端口（用 TCP 监听探测空闲号）。
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
     * 启动 KCP 服务端。
     *
     * @param port 端口
     * @return 服务端
     */
    private static KcpServer server(int port) {
        ServerSetting setting = ServerSetting.builder()
                .host("127.0.0.1")
                .port(port)
                .auto(false)
                .build();
        return new KcpServer(setting);
    }

    /**
     * 上行派发 + 服务端广播回传：验证注册、send、onSubscribe、publish 全链路。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(90)
    @DisplayName("KcpServer：注册后上行派发并广播回传")
    void requestResponseFlow() throws Exception {
        int port = freePort();
        KcpServer server = server(port);
        AtomicReference<String> received = new AtomicReference<>();
        server.onSubscribe("echo", (topic, payload) -> {
            received.set(payload);
            server.publish("resp", "pong:" + payload);
        });
        server.start();

        KcpClient client = new KcpClient("cid-1", "127.0.0.1:" + port);
        CountDownLatch gotResponse = new CountDownLatch(1);
        AtomicReference<String> responsePayload = new AtomicReference<>();
        client.onMessage((topic, payload) -> {
            if ("resp".equals(topic)) {
                responsePayload.set(payload);
                gotResponse.countDown();
            }
        });
        try {
            client.connect();
            client.send("echo", "hello-kcp");
            assertTrue(gotResponse.await(15, TimeUnit.SECONDS), "未收到服务端广播响应");
            assertEquals("pong:hello-kcp", responsePayload.get(), "响应内容不符");
            assertEquals("hello-kcp", received.get(), "服务端未收到上行消息");
        } finally {
            client.disconnect();
            server.stop();
        }
    }

    /**
     * 两个客户端都能收到服务端广播。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(90)
    @DisplayName("KcpServer：广播到达所有已注册客户端")
    void broadcastReachesAllClients() throws Exception {
        int port = freePort();
        KcpServer server = server(port);
        server.start();

        List<String> receivedA = new CopyOnWriteArrayList<>();
        List<String> receivedB = new CopyOnWriteArrayList<>();
        CountDownLatch gotA = new CountDownLatch(1);
        CountDownLatch gotB = new CountDownLatch(1);

        KcpClient clientA = new KcpClient("cid-a", "127.0.0.1:" + port);
        clientA.onMessage((topic, payload) -> {
            if ("broadcast".equals(topic)) {
                receivedA.add(payload);
                gotA.countDown();
            }
        });
        KcpClient clientB = new KcpClient("cid-b", "127.0.0.1:" + port);
        clientB.onMessage((topic, payload) -> {
            if ("broadcast".equals(topic)) {
                receivedB.add(payload);
                gotB.countDown();
            }
        });
        try {
            clientA.connect();
            clientB.connect();
            server.publish("broadcast", "news-1");
            assertTrue(gotA.await(15, TimeUnit.SECONDS), "客户端A未收到广播");
            assertTrue(gotB.await(15, TimeUnit.SECONDS), "客户端B未收到广播");
            assertEquals(List.of("news-1"), receivedA, "A 收到内容不符");
            assertEquals(List.of("news-1"), receivedB, "B 收到内容不符");
        } finally {
            clientA.disconnect();
            clientB.disconnect();
            server.stop();
        }
    }
}
