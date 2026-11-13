// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.sync.netty;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServerListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NettyWebSocketSyncClient} 与服务端 {@link NettyWebSocketSyncServer} 的端到端回归
 * （此前零用例）。
 *
 * <p>这条链路是「客户端 → 服务端」的唯一真实用法：{@code NettyWebSocketSyncFlow}
 * 就是拿这个客户端按 URL 建连收发。用例覆盖订阅推送、客户端上行、超 125 字节负载与
 * 非 ASCII 负载四种情况。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class NettyWebSocketSyncClientTest {

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
     * 监听配置。
     *
     * @param port 端口
     * @return 配置
     */
    private static ServerSetting setting(int port) {
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setAuto(false);
        return setting;
    }

    /**
     * 订阅推送 + 客户端上行必须走通。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("NettyWebSocketSyncClient：订阅推送与上行消息往返")
    void roundTrip() throws Exception {
        int port = freePort();
        NettyWebSocketSyncServer server = new NettyWebSocketSyncServer(setting(port));
        List<String> up = new CopyOnWriteArrayList<>();
        CountDownLatch serverGot = new CountDownLatch(1);
        server.addListener(new SyncServerListener() {
            @Override
            public void onMessage(String clientId, String topic, Object message) {
                up.add(topic + "|" + message);
                serverGot.countDown();
            }
        });
        server.start();
        try (NettyWebSocketSyncClient client =
                     new NettyWebSocketSyncClient("ws://127.0.0.1:" + port)) {
            client.connect();
            assertTrue(client.isConnected(), "客户端未进入已连接状态");

            List<String> down = new CopyOnWriteArrayList<>();
            CountDownLatch clientGot = new CountDownLatch(1);
            client.subscribe("news", (topic, message) -> {
                down.add(topic + "=" + message);
                clientGot.countDown();
            });

            server.publish("news", "hello-client");
            assertTrue(clientGot.await(10, TimeUnit.SECONDS), "客户端未收到服务端推送");
            assertEquals("news=hello-client", down.get(0), "推送内容不符");

            client.send("up", "from-client");
            assertTrue(serverGot.await(10, TimeUnit.SECONDS), "服务端未收到客户端上行消息");
            assertEquals("up|from-client", up.get(0), "上行内容不符");
        } finally {
            server.stop();
        }
    }

    /**
     * 超过 125 字节的负载必须双向完整送达（帧头需走扩展长度）。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("NettyWebSocketSyncClient：超 125 字节负载双向完整送达")
    void largePayload() throws Exception {
        String payload = "y".repeat(4000);
        int port = freePort();
        NettyWebSocketSyncServer server = new NettyWebSocketSyncServer(setting(port));
        List<String> up = new CopyOnWriteArrayList<>();
        CountDownLatch serverGot = new CountDownLatch(1);
        server.addListener(new SyncServerListener() {
            @Override
            public void onMessage(String clientId, String topic, Object message) {
                up.add(String.valueOf(message));
                serverGot.countDown();
            }
        });
        server.start();
        try (NettyWebSocketSyncClient client =
                     new NettyWebSocketSyncClient("ws://127.0.0.1:" + port)) {
            client.connect();

            List<String> down = new CopyOnWriteArrayList<>();
            CountDownLatch clientGot = new CountDownLatch(1);
            client.subscribe("big", (topic, message) -> {
                down.add(String.valueOf(message));
                clientGot.countDown();
            });

            server.publish("big", payload);
            assertTrue(clientGot.await(10, TimeUnit.SECONDS), "客户端未收到大负载推送");
            assertEquals(payload.length(), down.get(0).length(), "下行大负载被截断");

            client.send("big", payload);
            assertTrue(serverGot.await(10, TimeUnit.SECONDS), "服务端未收到大负载上行消息");
            assertEquals(payload.length(), up.get(0).length(), "上行大负载被截断");
        } finally {
            server.stop();
        }
    }

    /**
     * 非 ASCII 负载必须原样送达（帧负载按字节读，不能按字符读）。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("NettyWebSocketSyncClient：非 ASCII 负载原样送达")
    void utf8Payload() throws Exception {
        String payload = "中文负载：同步-测试";
        int port = freePort();
        NettyWebSocketSyncServer server = new NettyWebSocketSyncServer(setting(port));
        List<String> up = new CopyOnWriteArrayList<>();
        CountDownLatch serverGot = new CountDownLatch(1);
        server.addListener(new SyncServerListener() {
            @Override
            public void onMessage(String clientId, String topic, Object message) {
                up.add(String.valueOf(message));
                serverGot.countDown();
            }
        });
        server.start();
        try (NettyWebSocketSyncClient client =
                     new NettyWebSocketSyncClient("ws://127.0.0.1:" + port)) {
            client.connect();

            List<String> down = new CopyOnWriteArrayList<>();
            CountDownLatch clientGot = new CountDownLatch(1);
            client.subscribe("cn", (topic, message) -> {
                down.add(String.valueOf(message));
                clientGot.countDown();
            });

            server.publish("cn", payload);
            assertTrue(clientGot.await(10, TimeUnit.SECONDS), "客户端未收到非 ASCII 推送");
            assertEquals(payload, down.get(0), "下行非 ASCII 负载被破坏");

            client.send("cn", payload);
            assertTrue(serverGot.await(10, TimeUnit.SECONDS), "服务端未收到非 ASCII 上行消息");
            assertEquals(payload, up.get(0), "上行非 ASCII 负载被破坏");
        } finally {
            server.stop();
        }
    }
}
