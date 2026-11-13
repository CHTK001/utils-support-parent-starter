// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.server.websocket;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdkWebSocketServer} 的真实链路回归。
 *
 * <p>此前只有反射断言（校验 broadcaster 字段类型），没有任何收发用例，
 * 也正因为没人跑过，两个缺陷一直藏着：</p>
 * <ol>
 *   <li><b>handler 的返回值从不回写</b>：{@code SimpleServerResponse.writeRaw} 是空实现，
 *       readFrames 执行完 handler 也不发帧——表现为"能收到消息，但客户端永远收不到回复"。</li>
 *   <li><b>64 位长度字段顺序反了</b>：{@code >64KB} 的帧先写低 4 字节再写 4 个 0，
 *       客户端按协议解析会得到错误长度。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.52
 */
class JdkWebSocketServerTest {

    /**
     * 大帧负载大小（字节），必须大于 64KB 才能走到 64 位长度分支。
     */
    private static final int BIG_PAYLOAD = 100_000;

    /**
     * 监听配置。
     *
     * @return 配置
     */
    private static ServerSetting setting() {
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(0);
        setting.setAuto(false);
        return setting;
    }

    /**
     * 完成 WebSocket 握手。
     *
     * @param port 端口
     * @return 已握手的连接
     * @throws Exception 握手失败
     */
    private static Socket wsConnect(int port) throws Exception {
        Socket socket = new Socket();
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

        InputStream in = socket.getInputStream();
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
        String response = head.toString(StandardCharsets.US_ASCII);
        if (!response.startsWith("HTTP/1.1 101")) {
            socket.close();
            throw new IllegalStateException("握手失败: " + response);
        }
        return socket;
    }

    /**
     * 发送带掩码的文本帧。
     *
     * @param out  输出流
     * @param text 文本
     * @throws Exception 写失败
     */
    private static void sendTextFrame(OutputStream out, String text) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(payload.length + 14);
        buffer.write(0x81);
        if (payload.length < 126) {
            buffer.write(0x80 | payload.length);
        } else if (payload.length <= 0xFFFF) {
            buffer.write(0x80 | 126);
            buffer.write((payload.length >> 8) & 0xFF);
            buffer.write(payload.length & 0xFF);
        } else {
            buffer.write(0x80 | 127);
            for (int i = 0; i < 4; i++) {
                buffer.write(0);
            }
            for (int i = 3; i >= 0; i--) {
                buffer.write((payload.length >> (i * 8)) & 0xFF);
            }
        }
        byte[] mask = {0x11, 0x22, 0x33, 0x44};
        buffer.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) {
            buffer.write(payload[i] ^ mask[i % 4]);
        }
        out.write(buffer.toByteArray());
        out.flush();
    }

    /**
     * 读一帧服务端发来的（无掩码）帧。
     *
     * @param in 输入流
     * @return 负载；连接关闭时返回 null
     * @throws Exception 读取失败
     */
    private static byte[] readServerFrame(InputStream in) throws Exception {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 < 0 || b1 < 0) {
            return null;
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
        int offset = 0;
        while (offset < payload.length) {
            int read = in.read(payload, offset, payload.length - offset);
            if (read < 0) {
                return null;
            }
            offset += read;
        }
        return payload;
    }

    /**
     * handler 设置的返回值必须真正发回客户端。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("JdkWebSocketServer：handler 结果回写给客户端")
    void writesHandlerResultBack() throws Exception {
        JdkWebSocketServer server = new JdkWebSocketServer(setting());
        server.onSubscribe("demo", (request, response) ->
                response.setResult("echo:" + request.getBodyString()));
        server.start();
        try {
            int port = server.getPort();
            assertTrue(port > 0, "服务器未绑定端口");
            try (Socket client = wsConnect(port)) {
                sendTextFrame(client.getOutputStream(), "demo\nhello");
                byte[] frame = readServerFrame(client.getInputStream());
                assertTrue(frame != null, "客户端没有收到任何回复（handler 结果未回写）");
                assertEquals("echo:hello", new String(frame, StandardCharsets.UTF_8),
                        "回写内容不符");
            }
        } finally {
            server.stop();
        }
    }

    /**
     * 超过 64KB 的回复必须带正确的 64 位长度。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(120)
    @DisplayName("JdkWebSocketServer：100KB 回复的长度字段正确")
    void writesLargeFrameWithCorrectLength() throws Exception {
        String big = "x".repeat(BIG_PAYLOAD);
        JdkWebSocketServer server = new JdkWebSocketServer(setting());
        server.onSubscribe("big", (request, response) -> response.setResult(big));
        server.start();
        try {
            try (Socket client = wsConnect(server.getPort())) {
                sendTextFrame(client.getOutputStream(), "big\n");
                byte[] frame = readServerFrame(client.getInputStream());
                assertTrue(frame != null, "大帧未收到（64 位长度字段可能写错，客户端解析失败）");
                assertEquals(BIG_PAYLOAD, frame.length, "大帧长度不符");
            }
        } finally {
            server.stop();
        }
    }
}
