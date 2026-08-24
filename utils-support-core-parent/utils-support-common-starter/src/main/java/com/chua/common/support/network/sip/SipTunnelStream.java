package com.chua.common.support.network.sip;

import com.chua.common.support.lang.algorithm.hmac.HMacUtils;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.function.Consumer;

/**
 * frp 数据平面客户端连接：与 SipServer 数据平面建立独立 TCP 长连接，
 * 握手携带签名后进入裸字节流双向透传，无 Base64 开销、无帧封装。
 *
 * <p>连接建立后首先发送 {@code CONNECT|channelId|role|signature} 握手行，
 * 随后即为裸字节流（与 TcpProxyServer 相同模式）。</p>
 *
 * <p>可选端到端加密（AES-256-GCM）：开启后握手行之后的载荷按帧加密传输，
 * 帧格式 {@code [4B 长度][12B nonce][密文+16B 认证标签]}，密钥由共享 token 派生，
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
     * GCM nonce 长度（字节）
     */
    private static final int NONCE_LEN = 12;

    /**
     * GCM 认证标签长度（字节）
     */
    private static final int TAG_LEN = 16;

    /**
     * 单帧最大长度（含 nonce 与标签），超出视为协议错误并断链
     */
    private static final int MAX_FRAME_LEN = 64 * 1024 + NONCE_LEN + TAG_LEN;

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
     * 随机数发生器（生成每帧 nonce）
     */
    private final SecureRandom random = new SecureRandom();

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
     * @param encrypt   是否启用端到端加密（两侧需一致）
     * @throws IOException IO 异常
     */
    SipTunnelStream(String host, int port, String channelId, String role, String token, boolean encrypt) throws IOException {
        this.channelId = channelId;
        this.encrypt = encrypt;
        this.aesKey = encrypt ? AesGcmUtils.deriveKey(token) : null;
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
        String signature = HMacUtils.hmacSha256Hex(token, channelId + role);
        out.write((SipProtocol.PREFIX_CONNECT + SipProtocol.SEPARATOR
                + channelId + SipProtocol.SEPARATOR + role + SipProtocol.SEPARATOR + signature + "\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * 由共享 token 派生 AES-256 密钥（SHA-256）。
     *
     * @param token 共享令牌
     * @return AES 密钥
     */
    private static SecretKeySpec deriveKey(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new SecretKeySpec(digest.digest(token.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("SIP 加密密钥派生失败", e);
        }
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
            if (!encrypt) {
                out.write(payload);
            } else {
                out.write(encryptFrame(payload));
            }
            out.flush();
        }
    }

    /**
     * 将明文加密为一帧：[4B 长度][12B nonce][密文+标签]。
     *
     * @param plain 明文
     * @return 完整帧
     * @throws IOException 加密失败
     */
    private byte[] encryptFrame(byte[] plain) throws IOException {
        try {
            byte[] nonce = new byte[NONCE_LEN];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_LEN * 8, nonce));
            byte[] cipherText = cipher.doFinal(plain);
            ByteBuffer buffer = ByteBuffer.allocate(4 + NONCE_LEN + cipherText.length);
            buffer.putInt(NONCE_LEN + cipherText.length);
            buffer.put(nonce);
            buffer.put(cipherText);
            return buffer.array();
        } catch (Exception e) {
            throw new IOException("SIP 帧加密失败: " + channelId, e);
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
                InputStream in = socketIn;
                if (!encrypt) {
                    readRaw(in, consumer);
                } else {
                    readFrames(in, consumer);
                }
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
     * 裸字节流读取（未加密模式）。
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
     * 帧模式读取（加密模式）：逐帧解密后回调。
     *
     * @param in       输入流
     * @param consumer 数据消费者
     * @throws IOException IO 异常或认证失败
     */
    private void readFrames(InputStream in, Consumer<byte[]> consumer) throws IOException {
        while (!closed) {
            byte[] lenBytes = readFully(in, 4);
            int len = ByteBuffer.wrap(lenBytes).getInt();
            if (len <= 0 || len > MAX_FRAME_LEN) {
                throw new IOException("SIP 加密帧长度非法: " + len);
            }
            byte[] frame = readFully(in, len);
            byte[] nonce = new byte[NONCE_LEN];
            byte[] cipherText = new byte[len - NONCE_LEN];
            System.arraycopy(frame, 0, nonce, 0, NONCE_LEN);
            System.arraycopy(frame, NONCE_LEN, cipherText, 0, cipherText.length);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_LEN * 8, nonce));
                byte[] plain = cipher.doFinal(cipherText);
                if (!closed) {
                    consumer.accept(plain);
                }
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException("SIP 解密失败: " + channelId, e);
            }
        }
    }

    /**
     * 阻塞读满指定长度。
     *
     * @param in 输入流
     * @param n  期望长度
     * @return 读满的字节数组
     * @throws IOException 流结束或 IO 异常
     */
    private byte[] readFully(InputStream in, int n) throws IOException {
        byte[] data = new byte[n];
        int offset = 0;
        while (offset < n) {
            int read = in.read(data, offset, n - offset);
            if (read == -1) {
                throw new IOException("SIP 加密帧不完整，连接已关闭");
            }
            offset += read;
        }
        return data;
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
