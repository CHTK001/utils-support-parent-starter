package com.chua.webrtc.server;

// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.webrtc.model.WebRtcSignalingRequest;
import com.chua.webrtc.model.WebRtcSignalingResponse;

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket 信令 Handler。
 *
 * <p>负责：
 * <ul>
 *   <li>WebSocket 握手升级（HTTP 101，含 Sec-WebSocket-Accept）</li>
 *   <li>帧收发（文本/二进制，兼容客户端 Mask）</li>
 *   <li>信令消息（offer/answer/ice-candidate）分发到 {@link WebRtcServer}</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public class WebRtcSignalingHandler implements ServerHandler {

    /**
     * WebSocket 握手 GUID（RFC 6455）
     */
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /**
     * 处理信令连接（占位用，实际连接由 {@link WebRtcServer} 接管）
     */
    @Override
    public void handle(ServerRequest request, ServerResponse response) {
        response.end();
    }

    /**
     * 处理 WebSocket 连接
     *
     * @param socket 已确认的 Socket 连接
     * @param server 对端的 WebRtcServer
     */
    public static void handleConnection(Socket socket, WebRtcServer server) {
        AtomicBoolean running = new AtomicBoolean(true);
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String handshake = readHandshake(in);
            if (!isWebSocketUpgradeRequest(handshake)) {
                socket.close();
                return;
            }
            writeHandshakeResponse(out, secWebSocketKey(handshake));

            // 通过 URL 查询参数或首个帧获取 sessionId
            String sessionId = null;
            byte[] frame;
            while (running.get()) {
                frame = readFrame(in, out);
                if (frame == null) {
                    break;
                }
                String message = new String(frame, StandardCharsets.UTF_8);
                if (message.isEmpty()) {
                    continue;
                }
                // 首个帧：peerId/sessionId（由浏览器在连接后主动上报）
                if (sessionId == null) {
                    sessionId = extractPeerId(message);
                    if (sessionId != null) {
                        server.register(sessionId, socket);
                    }
                }
                onMessage(socket, server, sessionId != null ? sessionId : "session-0", message, running);
            }
        } catch (Exception e) {
            // 忽略
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    /**
     * 处理接收到的 WebSocket Frame
     */
    private static void onMessage(Socket socket, WebRtcServer server, String sessionId, String message, AtomicBoolean running) {
        running.set(false);
        try {
            if (message.isEmpty()) {
                running.set(true);
                return;
            }
            WebRtcSignalingRequest request = WebRtcSignalingRequest.of(message);
            WebRtcSignalingResponse response = server.receiveSignaling(sessionId, request);
            writeFrame(socket, response.toJson());
            running.set(true);
        } catch (Exception e) {
            // 忽略
        }
    }

    private static boolean isWebSocketUpgradeRequest(String request) {
        return request.contains("Upgrade: websocket") && request.contains("Connection: Upgrade");
    }

    /**
     * 回写 101 握手响应（必须携带 Sec-WebSocket-Accept，否则浏览器会拒绝连接）
     *
     * @param out 输出流
     * @param key 客户端 Sec-WebSocket-Key
     * @throws Exception 写出失败
     */
    private static void writeHandshakeResponse(OutputStream out, String key) throws Exception {
        StringBuilder sb = new StringBuilder("HTTP/1.1 101 Switching Protocols\r\n");
        sb.append("Upgrade: websocket\r\n");
        sb.append("Connection: Upgrade\r\n");
        if (key != null && !key.isEmpty()) {
            sb.append("Sec-WebSocket-Accept: ").append(acceptKey(key)).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /**
     * 计算 Sec-WebSocket-Accept
     *
     * @param key 客户端 Sec-WebSocket-Key
     * @return Base64(SHA1(key + GUID))
     * @throws Exception 摘要算法不可用
     */
    private static String acceptKey(String key) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] sha1 = digest.digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(sha1);
    }

    /**
     * 从握手请求中提取 Sec-WebSocket-Key
     *
     * @param handshake 握手请求文本
     * @return 客户端 Key，未找到时为 null
     */
    private static String secWebSocketKey(String handshake) {
        for (String line : handshake.split("\r\n")) {
            int idx = line.indexOf(':');
            if (idx > 0 && "Sec-WebSocket-Key".equalsIgnoreCase(line.substring(0, idx).trim())) {
                return line.substring(idx + 1).trim();
            }
        }
        return null;
    }

    private static String readHandshake(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[1024];
        int read = in.read(buf);
        while (read > 0) {
            sb.append(new String(buf, 0, read, StandardCharsets.ISO_8859_1));
            if (sb.indexOf("\r\n\r\n") != -1) {
                break;
            }
            read = in.read(buf);
        }
        return sb.toString();
    }

    /**
     * 读取 WebSocket 数据帧（兼容客户端 Mask，并处理 ping/pong/close 控制帧）
     *
     * @param in  输入流
     * @param out 输出流（用于回复 pong）
     * @return 数据帧载荷；close 帧返回 null；控制帧返回空数组
     */
    private static byte[] readFrame(InputStream in, OutputStream out) throws Exception {
        int head = in.read();
        if (head < 0) {
            return null;
        }
        int opcode = head & 0x0f;
        long len = in.read() & 0x7f;
        // [P3C A20 豁免] WebSocket 扩展长度解析，数值为协议约定
        if (len == 126) {
            len = ((long) in.read() << 8) | in.read();
        } else if (len == 127) {
            len = ((long) in.read() << 56) | ((long) in.read() << 48) | ((long) in.read() << 40)
                    | ((long) in.read() << 32) | ((long) in.read() << 24) | ((long) in.read() << 16)
                    | ((long) in.read() << 8) | in.read();
        }
        boolean masked = (head & 0x80) != 0;
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask);
        }
        if (len > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("WebSocket frame too large: " + len);
        }
        byte[] payload = new byte[(int) len];
        readFully(in, payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
        }
        switch (opcode) {
            case 0x8:
                // close
                return null;
            case 0x9:
                // ping → pong
                writeControlFrame(out, 0xA, payload);
                return new byte[0];
            case 0xA:
                // pong
                return new byte[0];
            case 0x1:
            case 0x2:
                return payload;
            default:
                // 连续帧等：按数据帧处理
                return payload;
        }
    }

    /**
     * 从 InputStream 定长读取
     */
    private static void readFully(InputStream in, byte[] buf) throws Exception {
        int total = 0;
        while (total < buf.length) {
            int readCount = in.read(buf, total, buf.length - total);
            if (readCount < 0) {
                throw new EOFException("End of stream");
            }
            total += readCount;
        }
    }

    /**
     * 写入 WebSocket 数据帧（服务端不 Mask）
     *
     * <p>同一 Socket 可能被信令线程与 WebRTC 回调线程同时写入，
     * 因此以 Socket 为锁串行化，避免帧交错。</p>
     *
     * @param socket  Socket
     * @param message 文本消息
     * @throws Exception 写出失败
     */
    public static void writeFrame(Socket socket, String message) throws Exception {
        synchronized (socket) {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            writeFrame(socket.getOutputStream(), 0x1, bytes);
        }
    }

    /**
     * 写入控制帧（ping/pong）
     *
     * @param out     输出流
     * @param opcode  操作码
     * @param payload 载荷
     * @throws Exception 写出失败
     */
    private static void writeControlFrame(OutputStream out, int opcode, byte[] payload) throws Exception {
        synchronized (out) {
            writeFrame(out, opcode, payload);
        }
    }

    /**
     * 写出一个完整 WebSocket 帧（FIN=1）
     *
     * @param out     输出流
     * @param opcode  操作码（0x1 文本 / 0x2 二进制 / 0xA pong）
     * @param payload 载荷
     * @throws Exception 写出失败
     */
    private static void writeFrame(OutputStream out, int opcode, byte[] payload) throws Exception {
        int len = payload.length;
        int headerLen = len <= 125 ? 2 : (len <= 65535 ? 4 : 10);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(headerLen + len);
        buffer.put((byte) (0x80 | opcode));
        // [P3C A20 豁免] WebSocket 帧扩展长度字面量，数值为协议约定
        if (len <= 125) {
            buffer.put((byte) len);
        } else if (len <= 65535) {
            buffer.put((byte) 126);
            buffer.put((byte) (len >> 8));
            buffer.put((byte) (len & 0xFF));
        } else {
            buffer.put((byte) 127);
            buffer.putLong(len);
        }
        buffer.put(payload);
        out.write(buffer.array(), 0, buffer.position());
        out.flush();
    }

    private static String extractPeerId(String message) {
        try {
            JsonObject json = Json.fromJson(message, JsonObject.class);
            if (json == null || !json.containsKey("peerId")) {
                return null;
            }
            return String.valueOf(json.get("peerId"));
        } catch (Exception ignore) {
            return null;
        }
    }
}
