// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServerListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket 广播与帧编解码的真实链路验证。
 *
 * <p>两处修复必须有护栏：</p>
 * <ol>
 *   <li><b>广播不能阻塞调用线程</b>：百万连接时 {@code publish()} 同步逐条写会阻塞数秒以上，
 *       一个不读数据的客户端足以让整轮广播卡死。改为异步分片扇出后，调用方应立即返回。</li>
 *   <li><b>大帧必须能解析</b>：长度前缀 127 表示 8 字节扩展长度，
 *       {@code WebSocketSyncServer} 原先只读 4 字节 → 长度恒为 0，后续字节全部错位。
 *       顺带把逐字节 {@code read()} 改成批量读（一帧 3 次 syscall 而不是 2~14 次）。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.52
 */
class WebSocketBroadcastAndFrameTest {

    /**
     * 取一个空闲端口。
     *
     * @return 端口
     * @throws Exception 套接字失败
     */
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 建一条完成握手的 WebSocket 连接。
     *
     * @param port     端口
     * @param rcvBuf   客户端接收缓冲（慢消费者场景要压小）
     * @return 连接
     * @throws Exception 握手失败
     */
    private static Socket wsConnect(int port, int rcvBuf) throws Exception {
        Socket socket = new Socket();
        if (rcvBuf > 0) {
            socket.setReceiveBufferSize(rcvBuf);
        }
        socket.connect(new InetSocketAddress("127.0.0.1", port), 10000);
        socket.setSoTimeout(20000);
        socket.setTcpNoDelay(true);
        String key = Base64.getEncoder().encodeToString(new byte[16]);
        String request = "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key
                + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String response = readHttpHeaders(socket.getInputStream());
        if (!response.startsWith("HTTP/1.1 101")) {
            socket.close();
            throw new IllegalStateException("握手失败: " + response);
        }
        return socket;
    }

    /**
     * 读 HTTP 响应头（到空行为止）。
     *
     * @param in 输入流
     * @return 响应头
     * @throws Exception 读取失败
     */
    private static String readHttpHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        int p1 = -1;
        int p2 = -1;
        int p3 = -1;
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (p1 == '\r' && p2 == '\n' && p3 == '\r' && b == '\n') {
                break;
            }
            p1 = p2;
            p2 = p3;
            p3 = b;
        }
        return buf.toString(StandardCharsets.US_ASCII);
    }

    /**
     * 发送带掩码的文本帧（按长度选择 7bit / 16bit / 64bit 前缀）。
     *
     * @param socket 连接
     * @param text   文本
     * @throws Exception 写失败
     */
    private static void sendTextFrame(Socket socket, String text) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buf = new ByteArrayOutputStream(payload.length + 14);
        buf.write(0x81);
        if (payload.length < 126) {
            buf.write(0x80 | payload.length);
        } else if (payload.length <= 0xFFFF) {
            buf.write(0x80 | 126);
            buf.write((payload.length >> 8) & 0xFF);
            buf.write(payload.length & 0xFF);
        } else {
            buf.write(0x80 | 127);
            long len = payload.length;
            for (int i = 7; i >= 0; i--) {
                buf.write((int) ((len >> (i * 8)) & 0xFF));
            }
        }
        byte[] mask = {0x12, 0x34, 0x56, 0x78};
        buf.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) {
            buf.write(payload[i] ^ mask[i % 4]);
        }
        OutputStream out = socket.getOutputStream();
        out.write(buf.toByteArray());
        out.flush();
    }

    /**
     * 读一帧服务端发来的（无掩码）文本帧。
     *
     * @param in 输入流
     * @return 负载
     * @throws Exception 读取失败
     */
    private static byte[] readServerFrame(InputStream in) throws Exception {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 < 0 || b1 < 0) {
            throw new IllegalStateException("对端已关闭");
        }
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
        int off = 0;
        while (off < payload.length) {
            int r = in.read(payload, off, payload.length - off);
            if (r < 0) {
                throw new IllegalStateException("帧读取中连接关闭");
            }
            off += r;
        }
        return payload;
    }

    /**
     * 慢消费者（不读数据）在场时，publish 必须立即返回。
     *
     * <p>同步实现会在第一帧填满内核发送/接收缓冲后阻塞在 {@code write} 上；
     * 异步扇出只是把帧塞进有界队列，调用方立刻返回。</p>
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(120)
    @DisplayName("慢消费者存在时 publish 不阻塞调用线程")
    void publishDoesNotBlockOnSlowConsumer() throws Exception {
        int port = freePort();
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setAuto(false);
        WebSocketSyncServer server = new WebSocketSyncServer(setting);

        // 接收缓冲压到 4KB 且完全不去读：服务端写满内核缓冲后必然阻塞
        Socket slowClient = null;
        try {
            server.start();
            slowClient = wsConnect(port, 4096);
            Thread.sleep(200L);

            String big = "x".repeat(64 * 1024);
            // 在独立线程里发布并**有界等待**：同步实现在慢消费者上会阻塞在 write 上，
            // 若直接在测试线程里跑，用例会挂死（@Timeout 也打断不了 socket write），
            // 那样失败模式是"卡住"而不是"红"。有界等待把它变成确定的断言失败。
            java.util.concurrent.atomic.AtomicLong elapsed = new java.util.concurrent.atomic.AtomicLong(-1L);
            Thread publisher = new Thread(() -> {
                long startNs = System.nanoTime();
                for (int i = 0; i < 50; i++) {
                    server.publish("topic", big);
                }
                elapsed.set((System.nanoTime() - startNs) / 1_000_000L);
            }, "ws-publish-probe");
            publisher.setDaemon(true);
            publisher.start();
            publisher.join(5000L);

            assertFalse(publisher.isAlive(),
                    "慢消费者在场时 publish 5s 内未返回（阻塞在内核写缓冲上）；"
                            + "百万连接下会直接卡死业务线程");
            long elapsedMs = elapsed.get();

            System.out.println("[ws-async] 50 次 64KB 广播 elapsedMs=" + elapsedMs);
            System.out.flush();

            assertTrue(elapsedMs < 5000L,
                    "慢消费者在场时 publish 阻塞了 " + elapsedMs + "ms（>5s）；"
                            + "说明仍在调用线程同步逐条写，百万连接下会直接卡死业务线程");
        } finally {
            if (slowClient != null) {
                slowClient.close();
            }
            server.stop();
        }
    }

    /**
     * 127 长度（8 字节扩展长度）的大帧必须被正确解析。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(120)
    @DisplayName("64KB+ 文本帧能完整解析（127 长度前缀）")
    void largeFrameIsParsed() throws Exception {
        int port = freePort();
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setMaxFrameSize(4 * 1024 * 1024);
        setting.setAuto(false);
        WebSocketSyncServer server = new WebSocketSyncServer(setting);

        AtomicReference<String> body = new AtomicReference<>();
        server.addListener(new SyncServerListener() {
            @Override
            public void onMessage(String clientId, String topic, Object message) {
                body.set((String) message);
            }
        });

        Socket client = null;
        try {
            server.start();
            client = wsConnect(port, 0);

            String payload = "y".repeat(200 * 1024);
            sendTextFrame(client, "topic:" + payload);

            long deadline = System.currentTimeMillis() + 20000L;
            while (body.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }
            assertNotNull(body.get(), "大帧未被分发（127 长度前缀解析失败会让长度恒为 0）");
            assertEquals(payload.length(), body.get().length(),
                    "大帧负载长度不符：期望 " + payload.length() + " 实际 " + body.get().length());
        } finally {
            if (client != null) {
                client.close();
            }
            server.stop();
        }
    }

    /**
     * 一次广播必须送达所有连接。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(120)
    @DisplayName("广播送达全部连接")
    void broadcastReachesAllClients() throws Exception {
        int port = freePort();
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setAuto(false);
        WebSocketSyncServer server = new WebSocketSyncServer(setting);

        List<Socket> clients = new ArrayList<>();
        try {
            server.start();
            for (int i = 0; i < 3; i++) {
                clients.add(wsConnect(port, 0));
            }
            Thread.sleep(300L);

            String text = "hello-broadcast";
            server.publish("topic", text);

            for (Socket client : clients) {
                byte[] frame = readServerFrame(client.getInputStream());
                String received = new String(frame, StandardCharsets.UTF_8);
                assertEquals("topic:" + text, received, "广播内容不符");
            }
        } finally {
            for (Socket client : clients) {
                try {
                    client.close();
                } catch (Exception ignored) {
                    // 清理失败不影响断言
                }
            }
            server.stop();
        }
    }
}
