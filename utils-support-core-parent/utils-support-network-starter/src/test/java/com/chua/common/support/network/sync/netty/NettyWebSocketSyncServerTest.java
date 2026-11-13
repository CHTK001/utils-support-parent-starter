// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.sync.netty;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServerListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NettyWebSocketSyncServer} 的真实链路回归（此前零用例）。
 *
 * <p>该类名字里虽写着 Netty，实际是手写的 socket + WebSocket 帧解析（与
 * {@code WebSocketSyncServer} 同契约），因此协议细节必须逐条用真实帧验证，
 * 否则只有把浏览器接上去才会发现问题。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class NettyWebSocketSyncServerTest {

    /**
     * RFC 6455 示例握手键，其 Accept 是公开固定值，可用来校验 SHA-1 实现。
     */
    private static final String RFC_KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    /**
     * RFC 6455 示例键对应的 Accept 值。
     */
    private static final String RFC_ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";

    /**
     * 握手请求（含 101 前必须完整读到分隔空行）。
     */
    private static final String HANDSHAKE = "GET / HTTP/1.1\r\n"
            + "Host: 127.0.0.1\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + RFC_KEY + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "\r\n";

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
     * 极简 WebSocket 客户端：只实现握手、发掩码帧、读服务端帧。
     */
    private static final class WsClient implements AutoCloseable {

        /**
         * 套接字。
         */
        private final Socket socket;

        /**
         * 输出流。
         */
        private final OutputStream out;

        /**
         * 输入流。
         */
        private final InputStream in;

        /**
         * 建连。
         *
         * @param port 端口
         * @throws Exception 连接失败
         */
        WsClient(int port) throws Exception {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 10000);
            socket.setSoTimeout(20000);
            socket.setTcpNoDelay(true);
            out = socket.getOutputStream();
            in = socket.getInputStream();
        }

        /**
         * 完成握手。
         *
         * @return 响应头全文
         * @throws Exception 读写失败
         */
        String handshake() throws Exception {
            out.write(HANDSHAKE.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int p1 = -1;
            int p2 = -1;
            int p3 = -1;
            int read;
            while ((read = in.read()) >= 0) {
                head.write(read);
                if (p1 == '\r' && p2 == '\n' && p3 == '\r' && read == '\n') {
                    break;
                }
                p1 = p2;
                p2 = p3;
                p3 = read;
            }
            return head.toString(StandardCharsets.US_ASCII);
        }

        /**
         * 发送带掩码的文本帧（客户端帧必须掩码）。
         *
         * @param text 文本
         * @throws Exception 写失败
         */
        void sendText(String text) throws Exception {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(data.length + 16);
            buffer.write(0x81);
            if (data.length <= 125) {
                buffer.write(0x80 | data.length);
            } else if (data.length <= 0xFFFF) {
                buffer.write(0x80 | 126);
                buffer.write((byte) ((data.length >> 8) & 0xFF));
                buffer.write((byte) (data.length & 0xFF));
            } else {
                buffer.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    buffer.write((byte) ((data.length >> (8 * i)) & 0xFF));
                }
            }
            byte[] mask = {0x11, 0x22, 0x33, 0x44};
            buffer.write(mask, 0, 4);
            for (int i = 0; i < data.length; i++) {
                buffer.write(data[i] ^ mask[i % 4]);
            }
            out.write(buffer.toByteArray());
            out.flush();
        }

        /**
         * 读一个服务端文本帧。
         *
         * <p>RFC 6455：服务端发出的帧<b>不得</b>带掩码，这里直接断言，读写不合规即失败。</p>
         *
         * @return 负载文本；连接关闭返回 null
         * @throws Exception 读失败
         */
        String readText() throws Exception {
            int b0 = in.read();
            if (b0 < 0) {
                return null;
            }
            int b1 = in.read();
            if (b1 < 0) {
                return null;
            }
            assertTrue((b1 & 0x80) == 0,
                    "服务端帧带了掩码位（RFC 6455 只允许客户端掩码），标准客户端会以 1006 关闭连接");
            long length = b1 & 0x7F;
            if (length == 126) {
                length = ((long) in.read() << 8) | in.read();
            } else if (length == 127) {
                length = 0L;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | in.read();
                }
            }
            byte[] payload = new byte[(int) length];
            int offset = 0;
            while (offset < payload.length) {
                int read = in.read(payload, offset, payload.length - offset);
                if (read < 0) {
                    return null;
                }
                offset += read;
            }
            return new String(payload, StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /**
     * port=0 时必须回写实际端口，否则调用方拿不到可连接的地址。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("NettyWebSocketSyncServer：port=0 时回写实际端口")
    void writesBackEphemeralPort() throws Exception {
        NettyWebSocketSyncServer server = new NettyWebSocketSyncServer(setting(0));
        server.start();
        try {
            int port = server.getPort();
            assertTrue(port > 0, "port=0 时未回写实际端口，调用方拿不到可连接地址");
            try (WsClient client = new WsClient(port)) {
                assertTrue(client.handshake().startsWith("HTTP/1.1 101"), "回写的端口连不上或握手失败");
            }
        } finally {
            server.stop();
        }
    }

    /**
     * 握手必须正确，且服务端帧不能带掩码。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("NettyWebSocketSyncServer：握手正确且服务端帧不带掩码")
    void handshakeAndUnmaskedServerFrame() throws Exception {
        int port = freePort();
        NettyWebSocketSyncServer server = new NettyWebSocketSyncServer(setting(port));
        server.start();
        try (WsClient client = new WsClient(port)) {
            String response = client.handshake();
            assertTrue(response.startsWith("HTTP/1.1 101"), "握手未返回 101：" + head(response));
            assertTrue(response.contains("Sec-WebSocket-Accept: " + RFC_ACCEPT),
                    "Sec-WebSocket-Accept 计算错误：" + head(response));

            server.publish("topic-a", "payload-1");
            assertEquals("topic-a:payload-1", client.readText(), "服务端帧内容不符");
        } finally {
            server.stop();
        }
    }

    /**
     * 64 位长度（127 分支）的大帧必须完整投递。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("NettyWebSocketSyncServer：64 位长度的大帧必须完整投递")
    void deliversLargeClientFrame() throws Exception {
        int port = freePort();
        NettyWebSocketSyncServer server = new NettyWebSocketSyncServer(setting(port));
        CountDownLatch latch = new CountDownLatch(1);
        List<String> received = new CopyOnWriteArrayList<>();
        server.addListener(new SyncServerListener() {
            @Override
            public void onMessage(String clientId, String topic, Object message) {
                received.add(topic + "|" + message);
                latch.countDown();
            }
        });
        server.start();
        try (WsClient client = new WsClient(port)) {
            client.handshake();
            client.sendText("big:" + "x".repeat(70000));
            assertTrue(latch.await(10, TimeUnit.SECONDS),
                    "大帧未被投递（127 长度分支只读了 4 字节，把高 4 位当成长度）");
            String message = received.get(0);
            assertTrue(message.startsWith("big|"), "主题解析不符：" + head(message));
            assertEquals(70000, message.length() - 4, "负载长度不符（大帧被截断）");
        } finally {
            server.stop();
        }
    }

    /**
     * 并发长连接不能被 accept 循环的同步兜底拖死。
     *
     * <p>执行器是 {@code 0..200} 的 {@code SynchronousQueue} + {@code CallerRunsPolicy}：
     * 第 201 条长连接会让 <b>accept 线程自己</b>去处理连接并阻塞在读帧上，
     * 之后所有新连接都进不来。生产环境表现为「连接数一过两百就再也接不上」。</p>
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(180)
    @DisplayName("NettyWebSocketSyncServer：并发长连接不被 accept 循环拖死")
    void acceptsManyConcurrentConnections() throws Exception {
        int port = freePort();
        NettyWebSocketSyncServer server = new NettyWebSocketSyncServer(setting(port));
        server.start();
        int perBatch = 100;
        int batches = 3;
        int total = perBatch * batches;
        AtomicInteger ok = new AtomicInteger();
        List<Socket> held = new CopyOnWriteArrayList<>();
        try {
            for (int batch = 0; batch < batches; batch++) {
                // 分批建连：Windows 有效 accept 队列约 200，一次性 300 条会先丢 SYN
                List<Thread> threads = new ArrayList<>(perBatch);
                for (int i = 0; i < perBatch; i++) {
                    Thread thread = Thread.ofVirtual().start(() -> {
                        Socket socket = null;
                        try {
                            socket = new Socket();
                            socket.connect(new InetSocketAddress("127.0.0.1", port), 8000);
                            socket.setSoTimeout(20000);
                            held.add(socket);
                            OutputStream out = socket.getOutputStream();
                            out.write(HANDSHAKE.getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                            // 读到 101 的首字节即算被服务端接纳
                            if (socket.getInputStream().read() == 'H') {
                                ok.incrementAndGet();
                            }
                        } catch (Exception e) {
                            if (socket != null) {
                                try {
                                    socket.close();
                                } catch (IOException ignored) {
                                    // 清理失败不影响断言
                                }
                            }
                        }
                    });
                    threads.add(thread);
                }
                for (Thread thread : threads) {
                    thread.join(30000);
                }
                if (batch < batches - 1) {
                    Thread.sleep(300);
                }
            }
            assertEquals(total, ok.get(), "并发长连接只有 " + ok.get() + "/" + total
                    + " 握手成功：accept 循环被执行器的同步兜底阻塞了");
        } finally {
            for (Socket socket : held) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 清理失败不影响断言
                }
            }
            server.stop();
        }
    }
}
