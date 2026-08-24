package com.chua.common.support.network.sip;

import com.chua.common.support.lang.algorithm.hmac.HMacUtils;
import com.chua.common.support.network.crypto.AesGcmUtils;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.spec.SecretKeySpec;
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
 * <p>连接建立后首先发送 {@code CONNECT|channelId|role|signature} 握手行，
 * 随后即为裸字节流（与 TcpProxyServer 相同模式）。</p>
 *
 * <p>可选流加密（AES-256-GCM 帧式，见 {@link com.chua.common.support.network.crypto.AesGcmUtils}）：
 * 开启后整个连接（含握手行）均在客户端侧加解密，密钥由共享 token 派生，
 * 服务端仅桥接密文无法窥探内容。加密开关要求 visitor 与 provider 两侧一致。</p>
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
     * 输出流（加密开启时为加密流）
     */
    private final OutputStream out;

    /**
     * 输入流（加密开启时为解密流）
     */
    private final InputStream socketIn;

    /**
     * 是否启用端到端加密（AES-256-GCM）
     */
    private final boolean encrypt;

    /**
     * 加密密钥（由共享 token 派生）
     */
    private final SecretKeySpec aesKey;

    /**
     * 是否已关闭
     */
    private volatile boolean closed;

    /**
     * 建立数据平面连接（携带签名握手）。
     *
     * @param host        数据平面地址
     * @param port        数据平面端口
     * @param channelId   通道标识
     * @param role        角色（visitor / provider）
     * @param sharedToken 共享令牌（流加密密钥派生源，与服务端 encryptKey 一致）
     * @param sessionToken 会话令牌（CONNECT 握手签名用）
     * @param encrypt     是否启用流加密（两侧需一致）
     * @throws IOException IO 异常
     */
    SipTunnelStream(String host, int port, String channelId, String role,
                    String sharedToken, String sessionToken, boolean encrypt) throws IOException {
        this.channelId = channelId;
        this.encrypt = encrypt;
        this.aesKey = encrypt ? AesGcmUtils.deriveKey(sharedToken) : null;
        this.socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new java.net.InetSocketAddress(host, port), 5000);
        OutputStream rawOut = socket.getOutputStream();
        InputStream rawIn = socket.getInputStream();
        if (encrypt) {
            // 加密包装必须先于握手行：服务端从连接首字节即开始解密
            rawOut = AesGcmUtils.encrypting(rawOut, aesKey);
            rawIn = AesGcmUtils.decrypting(rawIn, aesKey);
        }
        this.out = rawOut;
        this.socketIn = rawIn;
        String signature = HMacUtils.hmacSha256Hex(sessionToken, channelId + role);
        out.write((SipProtocol.PREFIX_CONNECT + SipProtocol.SEPARATOR
                + channelId + SipProtocol.SEPARATOR + role + SipProtocol.SEPARATOR + signature + "\n")
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
     * 发送字节流（加密开启时按 GCM 帧加密）。
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
     * 启动读循环，将收到的字节流（加密开启时先解密）回调给消费者。
     *
     * @param consumer 数据消费者
     */
    void startRead(Consumer<byte[]> consumer) {
        ThreadUtils.startVirtualThread("sip-data-stream-" + channelId, () -> {
            try {
                // socketIn 在加密模式下已包装为解密流，统一按裸字节读取
                readRaw(socketIn, consumer);
            } catch (Exception e) {
                if (!closed) {
                    log.debug("SIP 数据流读取异常: {}", e.getMessage());
                }
            } finally {
                close();
            }
        });
    }

    /**
     * 裸字节流读取。
     *
     * @param in       输入流
     * @param consumer 数据消费者
     * @throws IOException IO 异常
     */
    private void readRaw(InputStream in, Consumer<byte[]> consumer) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while (!closed && (n = in.read(buf)) != -1) {
            byte[] data = new byte[n];
            System.arraycopy(buf, 0, data, 0, n);
            if (!closed) {
                consumer.accept(data);
            }
        }
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
