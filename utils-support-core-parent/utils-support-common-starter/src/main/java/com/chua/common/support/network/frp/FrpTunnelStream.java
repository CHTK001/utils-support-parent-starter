package com.chua.common.support.network.frp;

import com.chua.common.support.lang.algorithm.hmac.HMacUtils;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * FRP 数据平面客户端连接：与 FrpServer 数据平面建立独立 TCP 长连接，
 * 握手携带签名后进入裸字节流双向透传，无 Base64 开销、无帧封装。
 *
 * <p>连接建立后首先发送 {@code CONNECT|channelId|role|signature} 握手行，
 * 随后即为裸字节流（与 TcpProxyServer 相同模式）。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class FrpTunnelStream {

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
     * 输入流
     */
    private final InputStream socketIn;

    /**
     * 是否已关闭
     */
    private volatile boolean closed;

    /**
     * 读循环死亡回调：数据面因 EOF/异常终止时触发，用于会话层联动拆除
     */
    private volatile Runnable onDeath;

    /**
     * 建立数据平面连接（携带签名握手）。
     *
     * @param host         数据平面地址
     * @param port         数据平面端口
     * @param channelId    通道标识
     * @param role         角色（visitor / provider）
     * @param sharedToken  共享令牌（与 FrpServer 相同）
     * @param sessionToken 会话令牌（CONNECT 握手签名用）
     * @throws IOException IO 异常
     */
    FrpTunnelStream(String host, int port, String channelId, String role,
                    String sharedToken, String sessionToken) throws IOException {
        this.channelId = channelId;
        this.socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new java.net.InetSocketAddress(host, port), 5000);
        this.out = socket.getOutputStream();
        this.socketIn = socket.getInputStream();
        String signature = HMacUtils.hmacSha256Hex(sessionToken, channelId + role);
        out.write((FrpProtocol.PREFIX_CONNECT + FrpProtocol.SEPARATOR
                + channelId + FrpProtocol.SEPARATOR + role + FrpProtocol.SEPARATOR + signature + "\n")
                .getBytes(StandardCharsets.UTF_8));
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
        send(payload, 0, payload.length);
    }

    /**
     * 发送字节流切片（零拷贝：直接写入底层输出流，不复制负载）。
     *
     * @param payload 负载字节
     * @param off     起始偏移
     * @param len     长度
     * @throws IOException IO 异常
     */
    void send(byte[] payload, int off, int len) throws IOException {
        synchronized (out) {
            out.write(payload, off, len);
            out.flush();
        }
    }

    /**
     * 启动读循环，将收到的字节流拷贝后回调给消费者（兼容旧接口）。
     *
     * @param consumer 数据消费者
     */
    void startRead(Consumer<byte[]> consumer) {
        startRead((buf, off, len) -> consumer.accept(Arrays.copyOfRange(buf, off, off + len)));
    }

    /**
     * 启动读循环，将收到的字节流以零拷贝切片回调给消费者：
     * 直接交付底层读缓冲的 {@code (buf, off, len)} 视图，不再逐次分配中间数组。
     *
     * @param consumer 数据消费者
     */
    void startRead(FrpByteConsumer consumer) {
        ThreadUtils.startVirtualThread("frp-data-stream-" + channelId, () -> {
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while (!closed && (n = socketIn.read(buf)) != -1) {
                    if (!closed) {
                        consumer.accept(buf, 0, n);
                    }
                }
            } catch (Exception e) {
                if (!closed) {
                    log.debug("FRP 数据流读取异常: {}", e.getMessage());
                }
            } finally {
                close();
                Runnable death = this.onDeath;
                if (death != null) {
                    death.run();
                }
            }
        });
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
        } catch (IOException e) {
            log.debug("关闭 FRP 隧道流失败，已忽略: {}", e.getMessage());
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

    /**
     * 注册读循环死亡回调：数据面连接因 EOF/异常终止时触发（主动关闭后亦兜底触发），
     * 供会话层联动拆除，避免「数据面已死、会话不知」导致的双向静默等待。
     *
     * @param onDeath 死亡回调
     */
    void onDeath(Runnable onDeath) {
        this.onDeath = onDeath;
    }
}