package com.chua.common.support.network.server.websocket;

import com.chua.common.support.network.server.request.ServerRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * WebSocket 协议工具类（RFC 6455）。
 *
 * <p>抽取自 {@link JdkWebSocketServer}，提供握手、帧编解码等协议级能力，
 * 供任何持有原始连接（{@link java.io.InputStream}/{@link java.io.OutputStream}）的
 * 服务器复用，例如 {@code NioHttpServer} 在请求头携带
 * {@code Upgrade: websocket} 时将连接升级为 WebSocket。</p>
 *
 * @author CH
 * @since 2026/08/15
 */
public final class WebSocketProtocol {

    /**
     * RFC 6455 规定的握手 GUID。
     */
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketProtocol() {
    }

    /**
     * 判断请求是否为 WebSocket 升级请求。
     *
     * @param request 服务器请求
     * @return true 表示携带 {@code Upgrade: websocket} 头
     */
    public static boolean isUpgradeRequest(ServerRequest request) {
        String upgrade = request.getHeader("Upgrade");
        if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade.trim())) {
            return false;
        }
        String connection = request.getHeader("Connection");
        if (connection == null) {
            return false;
        }
        for (String token : connection.split(",")) {
            if ("upgrade".equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算 Sec-WebSocket-Accept（RFC 6455 握手响应值）。
     *
     * @param key 请求头 Sec-WebSocket-Key
     * @return accept 值
     */
    public static String computeAccept(String key) {
        try {
            String combined = key + WS_MAGIC;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(combined.getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException("计算 Sec-WebSocket-Accept 失败", e);
        }
    }

    /**
     * 构建 101 Switching Protocols 握手响应报文。
     *
     * @param accept Sec-WebSocket-Accept 值
     * @return 握手响应字节
     */
    public static byte[] handshakeResponse(String accept) {
        return ("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 构建文本帧（服务端发送，不掩码）。
     *
     * @param payload 文本内容
     * @return 完整帧字节
     */
    public static byte[] textFrame(String payload) {
        return buildFrame((byte) 0x81, payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构建关闭帧（服务端发送，不掩码）。
     *
     * @param reason 关闭原因，可为 null
     * @return 完整帧字节
     */
    public static byte[] closeFrame(String reason) {
        byte[] reasonBytes = reason != null ? reason.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] frame = buildFrame((byte) 0x88, reasonBytes);
        // 关闭帧前 2 字节为关闭状态码（1000 = Normal Closure），未携带时补零
        if (reasonBytes.length > 0) {
            return frame;
        }
        return new byte[]{(byte) 0x88, 0x02, 0x03, (byte) 0xE8};
    }

    /**
     * 构建指定 opcode 的帧（服务端发送，不掩码，FIN=1）。
     */
    private static byte[] buildFrame(byte opcode, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 10);
        out.write(opcode);
        int len = data.length;
        if (len < 126) {
            out.write(len);
        } else if (len <= 0xFFFF) {
            out.write(126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((long) len >> (i * 8)) & 0xFF);
            }
        }
        out.writeBytes(data);
        return out.toByteArray();
    }

    /**
     * 从输入流读取一帧（处理客户端掩码、长度扩展）。
     *
     * @param in 连接输入流
     * @return 帧数据；EOF 时返回 null
     * @throws IOException 读取失败
     */
    public static Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int opcode = b0 & 0x0F;
        int b1 = in.read();
        if (b1 < 0) {
            return null;
        }
        boolean masked = (b1 & 0x80) != 0;
        long length = b1 & 0x7F;
        if (length == 126) {
            length = ((long) in.read() << 8) | in.read();
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | in.read();
            }
        }
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IOException("无效的帧长度: " + length);
        }
        byte[] maskKey = new byte[4];
        if (masked) {
            readFully(in, maskKey);
        }
        byte[] payload = new byte[(int) length];
        readFully(in, payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= maskKey[i & 3];
            }
        }
        return new Frame(opcode, payload);
    }

    /**
     * 读取完整数据到目标数组。
     */
    private static void readFully(InputStream in, byte[] target) throws IOException {
        int off = 0;
        while (off < target.length) {
            int r = in.read(target, off, target.length - off);
            if (r < 0) {
                throw new IOException("连接意外关闭");
            }
            off += r;
        }
    }

    /**
     * WebSocket 帧：opcode + 已去掩码的 payload。
     *
     * @param opcode  操作码（0x1 文本、0x2 二进制、0x8 关闭、0x9 ping、0xA pong）
     * @param payload 载荷
     */
    public record Frame(int opcode, byte[] payload) {
    }
}
