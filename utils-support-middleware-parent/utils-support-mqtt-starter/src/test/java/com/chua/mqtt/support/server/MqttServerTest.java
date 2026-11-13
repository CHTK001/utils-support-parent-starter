// [P3C A20 豁免] 测试断言与协议常量含魔法值场景，豁免。
package com.chua.mqtt.support.server;

import com.chua.common.support.network.server.ServerSetting;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MqttServer} 的真实链路回归（此前零用例）。
 *
 * <p>服务端是手写 socket 实现的 MQTT 3.1.1（CONNECT/CONNACK、SUBSCRIBE/SUBACK、
 * PUBLISH + QoS 确认、PINGREQ/PINGRESP、DISCONNECT），本测试用官方
 * <b>Eclipse Paho 客户端</b>走真实 TCP，按标准协议逐条验证，而不是自己拼报文自证。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class MqttServerTest {

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
     * 构造服务端。
     *
     * @param port 端口
     * @return 服务端
     */
    private static MqttServer server(int port) {
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setAuto(false);
        return new MqttServer(setting);
    }

    /**
     * 建一个 Paho 客户端（内存持久化，避免落盘残留）。
     *
     * @param port     端口
     * @param clientId 客户端标识
     * @return 客户端
     * @throws Exception 创建失败
     */
    private static MqttClient client(int port, String clientId) throws Exception {
        return new MqttClient("tcp://127.0.0.1:" + port, clientId, new MemoryPersistence());
    }

    /**
     * 连接选项（匿名、短 keepAlive 便于验证 PING）。
     *
     * @return 选项
     */
    private static MqttConnectOptions options() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(2);
        return options;
    }

    /**
     * 关闭客户端：Paho 要求先 disconnect 再 close（否则抛 32100 Client is connected）。
     *
     * @param client 客户端，可为 null
     */
    private static void closeClient(MqttClient client) {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (Exception ignored) {
            // 收尾失败不影响断言
        }
        try {
            client.close();
        } catch (Exception ignored) {
            // 收尾失败不影响断言
        }
    }

    /**
     * CONNECT 必须被接受，SUBSCRIBE 后服务端 publish 能被真实客户端收到。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("MqttServer：CONNECT/SUBSCRIBE + 服务端发布下行")
    void connectSubscribeAndReceive() throws Exception {
        int port = freePort();
        MqttServer server = server(port);
        server.start();
        MqttClient subscriber = client(port, "sub-" + UUID.randomUUID());
        try {
            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            subscriber.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    // 断言阶段看 isConnected，这里不处理
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    received.add(topic + "=" + new String(message.getPayload()));
                    latch.countDown();
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // 下行不需要
                }
            });
            subscriber.connect(options());
            assertTrue(subscriber.isConnected(), "CONNECT 未被接受（未收到 CONNACK）");
            subscriber.subscribe("order/1", 1);
            assertEquals(0, received.size());

            server.publish("order/1", "hello-from-server");
            assertTrue(latch.await(10, TimeUnit.SECONDS), "客户端未收到服务端 publish 的消息");
            assertEquals("order/1=hello-from-server", received.get(0), "下行内容不符");
        } finally {
            closeClient(subscriber);
            server.stop();
        }
    }

    /**
     * 客户端上行 PUBLISH（QoS 1）必须送达服务端本地订阅处理器并回 PUBACK。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("MqttServer：客户端上行 QoS1 送达本地处理器")
    void upstreamPublishQos1() throws Exception {
        int port = freePort();
        MqttServer server = server(port);
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        server.onSubscribe("up", (topic, payload) -> {
            received.add(topic + "=" + payload);
            latch.countDown();
        });
        server.start();
        MqttClient publisher = client(port, "pub-" + UUID.randomUUID());
        try {
            publisher.connect(options());
            // QoS1：服务端必须回 PUBACK，否则 Paho 的 publish 会阻塞到超时
            publisher.publish("up", new MqttMessage("from-client".getBytes()));
            assertTrue(latch.await(10, TimeUnit.SECONDS), "服务端未收到客户端上行消息");
            assertEquals("up=from-client", received.get(0), "上行内容不符");
        } finally {
            closeClient(publisher);
            server.stop();
        }
    }

    /**
     * 空闲超过 keepAlive 必须靠 PINGREQ/PINGRESP 保活，不能被踢下线。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("MqttServer：PINGREQ/PINGRESP 保活")
    void keepsAliveByPing() throws Exception {
        int port = freePort();
        MqttServer server = server(port);
        server.start();
        MqttClient client = client(port, "ping-" + UUID.randomUUID());
        try {
            client.connect(options());
            assertTrue(client.isConnected());
            // keepAlive=2s：空闲 4s 内 Paho 必须发出 PINGREQ 并收到 PINGRESP，否则连接被判定失活
            Thread.sleep(4_000L);
            assertTrue(client.isConnected(), "空闲超过 keepAlive 后连接被断开（PINGRESP 未处理）");
        } finally {
            closeClient(client);
            server.stop();
        }
    }

    /**
     * 断开后服务端必须清理会话（getSessionCount 归零）。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("MqttServer：DISCONNECT 后会话清理")
    void disconnectRemovesSession() throws Exception {
        int port = freePort();
        MqttServer server = server(port);
        server.start();
        try {
            MqttClient client = client(port, "bye-" + UUID.randomUUID());
            client.connect(options());
            client.subscribe("x", 0);
            assertTrue(awaitSessionCount(server, 1), "CONNECT 后会话数应为 1");
            client.disconnect();
            assertTrue(awaitSessionCount(server, 0), "DISCONNECT 后会话未清理");
            closeClient(client);
        } finally {
            server.stop();
        }
    }

    /**
     * 轮询等待会话数达到期望值。
     *
     * @param server 服务端
     * @param expect 期望会话数
     * @return 是否达到
     * @throws InterruptedException 等待被中断
     */
    private static boolean awaitSessionCount(MqttServer server, int expect) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (server.getSessionCount() == expect) {
                return true;
            }
            Thread.sleep(50L);
        }
        return server.getSessionCount() == expect;
    }

    /**
     * 未连接就发送会失败的守护：连接失败必须给出确定结果，不能挂起。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("MqttServer：端口关闭时连接快速失败")
    void failsFastWhenServerStopped() throws Exception {
        int port = freePort();
        AtomicBoolean failed = new AtomicBoolean(false);
        MqttClient client = client(port, "nope-" + UUID.randomUUID());
        try {
            try {
                client.connect(options());
            } catch (Exception e) {
                failed.set(true);
            }
            assertTrue(failed.get(), "服务端未启动时 connect 应失败");
        } finally {
            closeClient(client);
        }
    }
}
