// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.sync.netty;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NettyHttpSyncServer} 与 {@link NettyHttpSyncClient} 的端到端回归（此前零用例）。
 *
 * <p>HTTP 同步对是「客户端上行 / 服务端下行」的另一条真实链路。这里用两个真实客户端
 * 走一遍：服务端 publish → 客户端收到；客户端 A send → 客户端 B 收到。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class NettyHttpSyncServerTest {

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
     * 截取前 160 字符便于断言失败时阅读。
     *
     * @param text 文本
     * @return 摘要
     */
    private static String head(String text) {
        return text.substring(0, Math.min(160, text.length())).replace("\r\n", "|");
    }

    /**
     * 服务端下行 + 客户端上行都必须走通。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(90)
    @DisplayName("NettyHttpSyncServer：下行发布与上行消息往返")
    void roundTrip() throws Exception {
        int port = freePort();
        NettyHttpSyncServer server = new NettyHttpSyncServer(setting(port));
        server.start();
        try (NettyHttpSyncClient reader = new NettyHttpSyncClient("http://127.0.0.1:" + port);
             NettyHttpSyncClient writer = new NettyHttpSyncClient("http://127.0.0.1:" + port)) {
            reader.connect();
            writer.connect();

            List<String> inbox = new CopyOnWriteArrayList<>();
            CountDownLatch serverPush = new CountDownLatch(1);
            CountDownLatch clientPush = new CountDownLatch(1);
            reader.subscribe("news", (topic, message) -> {
                inbox.add(topic + "=" + message);
                serverPush.countDown();
            });
            reader.subscribe("up", (topic, message) -> {
                inbox.add(topic + "=" + message);
                clientPush.countDown();
            });

            server.publish("news", "hello-from-server");
            assertTrue(serverPush.await(20, TimeUnit.SECONDS), "客户端未收到服务端 publish 的消息");

            // 上行：A 发，B 收（send 用的是 topic/message 表单参数）
            writer.send("up", "from-client-a");
            assertTrue(clientPush.await(20, TimeUnit.SECONDS),
                    "上行消息未落到服务端队列（send 的表单参数名与解析口径不一致）");

            List<String> received = new CopyOnWriteArrayList<>(inbox);
            assertTrue(received.contains("news=hello-from-server"), "下行内容不符：" + received);
            assertTrue(received.contains("up=from-client-a"), "上行内容不符：" + received);
        } finally {
            server.stop();
        }
    }

    /**
     * port=0 时必须回写实际端口。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("NettyHttpSyncServer：port=0 时回写实际端口")
    void writesBackEphemeralPort() throws Exception {
        NettyHttpSyncServer server = new NettyHttpSyncServer(setting(0));
        server.start();
        try {
            int port = server.getPort();
            assertTrue(port > 0, "port=0 时未回写实际端口，调用方拿不到可连接地址");
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()
                    .send(HttpRequest.newBuilder()
                                    .uri(URI.create("http://127.0.0.1:" + port
                                            + "/api/sync/pull?clientId=x&topics=t&timeout=1"))
                                    .timeout(Duration.ofSeconds(10))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(204, response.statusCode(), "无消息时应回 204，实际：" + response.statusCode());
        } finally {
            server.stop();
        }
    }

    /**
     * send 端点只接受 POST。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("NettyHttpSyncServer：send 端点拒绝非 POST")
    void sendRejectsGet() throws Exception {
        int port = freePort();
        NettyHttpSyncServer server = new NettyHttpSyncServer(setting(port));
        server.start();
        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()
                    .send(HttpRequest.newBuilder()
                                    .uri(URI.create("http://127.0.0.1:" + port + "/api/sync/send"))
                                    .timeout(Duration.ofSeconds(5))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(405, response.statusCode(), "send 端点应拒绝 GET，实际：" + response.statusCode());
        } finally {
            server.stop();
        }
    }
}
