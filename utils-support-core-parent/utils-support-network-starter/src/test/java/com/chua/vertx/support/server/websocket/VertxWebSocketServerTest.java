// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.vertx.support.server.websocket;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VertxWebSocketServer} 的真实链路回归（此前零用例）。
 *
 * <p>该实现与两个自研 WS 实现的差异点在响应：它的 {@code setResult} 会直接
 * {@code ws.writeTextMessage(...)}，而自研 JDK 版曾漏掉回写。这里用真实帧验证一遍。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class VertxWebSocketServerTest {

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
     * 完成 WebSocket 握手。
     *
     * @param port 端口
     * @return 已握手连接
     * @throws Exception 握手失败
     */
    private static Socket wsConnect(int port) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 10000);
        socket.setSoTimeout(15000);
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
        byte[] mask = {0x21, 0x43, 0x65, 0x78};
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
     * @return 负载；连接关闭返回 null
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
     * 订阅处理器设置的返回值必须发回客户端。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxWebSocketServer：订阅处理器结果回写给客户端")
    void writesHandlerResultBack() throws Exception {
        int port = freePort();
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setAuto(false);

        VertxWebSocketServer server = new VertxWebSocketServer(setting);
        server.onSubscribe("demo", (request, response) ->
                response.setResult("echo:" + request.getBodyString()));
        server.start();
        try (Socket client = wsConnect(port)) {
            sendTextFrame(client.getOutputStream(), "demo\nhello");
            byte[] frame = readServerFrame(client.getInputStream());
            assertNotNull(frame, "客户端没有收到任何回复（处理器结果未回写）");
            assertTrue(new String(frame, StandardCharsets.UTF_8).contains("echo:hello"),
                    "回写内容不符: " + new String(frame, StandardCharsets.UTF_8));
        } finally {
            server.stop();
        }
    }
}
