package com.chua.common.support.network.sip;

import com.chua.common.support.lang.algorithm.hmac.HMacUtils;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * frp 数据平面客户端连接：与 SipServer 数据平面建立独立 TCP 长连接，
 * 握手携带签名后进入裸字节流双向透传，无 Base64 开销、无帧封装。
 *
 * <p>连接建立后首先发送 {@code channelId|role|signature} 握手行，
 * 随后即为裸字节流（与 TcpProxyServer 相同模式）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
class SipTunnelStream {

    /**
     * 角色：访问方
     */
    static final String ROLE_VISITOR = "visitor";

    /**
     * 角色：提供方
     */
    static final String ROLE_PROVIDER = "provider";

    /**
     * 通道标识
     */
    private final String channelId;

    /**
     * 底层数据连接
     */
    private final Socket socket;

    /**
     * 输出流
     */
    private final OutputStream out;

    /**
     * 是否已关闭
     */
    private volatile boolean closed;

    /**
     * 建立数据平面连接（携带签名握手）。
     *
     * @param host      数据平面地址
     * @param port      数据平面端口
     * @param channelId 通道标识
     * @param role      角色（visitor / provider）
     * @param token     认证令牌
     * @throws IOException IO 异常
     */
    SipTunnelStream(String host, int port, String channelId, String role, String token) throws IOException {
        this.channelId = channelId;
        this.socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new java.net.InetSocketAddress(host, port), 5000);
        this.out = socket.getOutputStream();
        String signature = HMacUtils.hmacSha256Hex(token, channelId + role);
        out.write((channelId + "|" + role + "|" + signature + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * 获取通道标识。
     *
     * @return 通道标识
     */
    String getChannelId() {
        return channelId;
    }

    /**
     * 发送字节流。
     *
     * @param payload 负载字节
     * @throws IOException IO 异常
     */
    void send(byte[] payload) throws IOException {
        synchronized (out) {
            out.write(payload);
            out.flush();
        }
    }

    /**
     * 启动读循环，将收到的裸字节流回调给消费者。
     *
     * @param consumer 数据消费者
     */
    void startRead(Consumer<byte[]> consumer) {
        ThreadUtils.newThread(() -> {
            try {
                InputStream in = socket.getInputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while (!closed && (n = in.read(buf)) != -1) {
                    byte[] data = new byte[n];
                    System.arraycopy(buf, 0, data, 0, n);
                    if (!closed) {
                        consumer.accept(data);
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    log.debug("SIP 数据流读取异常: {}", e.getMessage());
                }
            } finally {
                close();
            }
        }, "sip-data-stream-" + channelId).start();
    }

    /**
     * 关闭数据连接。
     */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * 是否已关闭。
     *
     * @return true 表示已关闭
     */
    boolean isClosed() {
        return closed;
    }
}
