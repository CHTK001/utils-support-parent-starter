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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SIP 多路复用数据面共享连接（客户端侧）。
 *
 * <p>单条 TCP 连接承载同角色的全部隧道数据：帧格式
 * {@code [4B 长度][16B channelId UUID][payload]}，payload 为空表示通道关闭标记。
 * 握手行 {@code MUXCONN|首个channelId|role|signature} 与 CONNECT 验签规则一致。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
class SipMuxConnection {

    /**
     * UUID 字节长度
     */
    private static final int CH_LEN = 16;

    /**
     * 单帧 payload 上限
     */
    private static final int MAX_PAYLOAD = 64 * 1024;

    /**
     * 所属客户端
     */
    private final SipClient client;

    /**
     * 角色（visitor / provider）
     */
    private final String role;

    /**
     * 底层 Socket
     */
    private final Socket socket;

    /**
     * 输出流（加密开启时为加密流）
     */
    private final OutputStream out;

    /**
     * 输入流（加密开启时为解密流）
     */
    private final InputStream in;

    /**
     * 已挂载的虚拟流（channelId → stream）
     */
    private final Map<String, SipMuxStream> streams = new ConcurrentHashMap<>();

    /**
     * 是否已关闭
     */
    private volatile boolean closed;

    private SipMuxConnection(SipClient client, String role, Socket socket, OutputStream out, InputStream in) {
        this.client = client;
        this.role = role;
        this.socket = socket;
        this.out = out;
        this.in = in;
    }

    /**
     * 建立多路复用连接（握手 MUXCONN|首个channelId|role|sig）。
     *
     * @param client         所属客户端
     * @param host           服务器地址
     * @param port           服务器端口
     * @param role           角色
     * @param sharedToken    共享令牌（流加密密钥派生）
     * @param sessionToken   会话令牌（握手签名）
     * @param firstChannelId 首个通道标识
     * @param encrypt        是否流加密
     * @return 多路复用连接
     * @throws IOException IO 异常
     */
    static SipMuxConnection open(SipClient client, String host, int port, String role,
                                 String sharedToken, String sessionToken, String firstChannelId,
                                 boolean encrypt) throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new java.net.InetSocketAddress(host, port), 5000);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        SecretKeySpec aesKey = null;
        if (encrypt) {
            aesKey = AesGcmUtils.deriveKey(sharedToken);
            out = AesGcmUtils.encrypting(out, aesKey);
            in = AesGcmUtils.decrypting(in, aesKey);
        }
        String signature = HMacUtils.hmacSha256Hex(sessionToken, firstChannelId + role);
        out.write((SipProtocol.PREFIX_MUXCONN + SipProtocol.SEPARATOR
                + firstChannelId + SipProtocol.SEPARATOR + role + SipProtocol.SEPARATOR + signature + "\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
        SipMuxConnection conn = new SipMuxConnection(client, role, socket, out, in);
        ThreadUtils.startVirtualThread("sip-mux-" + role, conn::readLoop);
        return conn;
    }

    /**
     * 挂载虚拟流。
     *
     * @param stream 虚拟流
     */
    void attach(SipMuxStream stream) {
        streams.put(stream.channelId(), stream);
    }

    /**
     * 发送一帧（payload 空表示通道关闭）。
     *
     * @param channelId 通道标识
     * @param payload   负载（可为空数组）
     * @throws IOException IO 异常
     */
    void sendFrame(String channelId, byte[] payload) throws IOException {
        synchronized (out) {
            byte[] ch = uuidBytes(channelId);
            ByteBuffer buffer = ByteBuffer.allocate(4 + CH_LEN + payload.length);
            buffer.putInt(CH_LEN + payload.length);
            buffer.put(ch);
            buffer.put(payload);
            out.write(buffer.array());
            out.flush();
        }
    }

    /**
     * 关闭指定通道（发送关闭标记并摘除虚拟流）。
     *
     * @param channelId 通道标识
     */
    void closeChannel(String channelId) {
        SipMuxStream stream = streams.remove(channelId);
        if (stream != null) {
            stream.markClosed();
        }
        try {
            sendFrame(channelId, new byte[0]);
        } catch (IOException e) {
            log.debug("SIP mux 关闭帧发送失败: {}", e.getMessage());
        }
    }

    /**
     * 关闭整个复用连接。
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
        for (SipMuxStream stream : streams.values()) {
            stream.peerClosed();
        }
        streams.clear();
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
     * 读循环：解帧并按 channelId 分发。
     */
    private void readLoop() {
        try {
            while (!closed) {
                byte[] header = readFully(4);
                int len = ByteBuffer.wrap(header).getInt();
                if (len < CH_LEN || len > CH_LEN + MAX_PAYLOAD) {
                    throw new IOException("SIP mux 帧长度非法: " + len);
                }
                byte[] frame = readFully(len);
                String channelId = uuidString(frame);
                byte[] payload = new byte[len - CH_LEN];
                System.arraycopy(frame, CH_LEN, payload, 0, payload.length);
                SipMuxStream stream = streams.get(channelId);
                if (stream == null) {
                    continue;
                }
                if (payload.length == 0) {
                    stream.peerClosed();
                } else {
                    stream.dispatch(payload);
                }
            }
        } catch (Exception e) {
            if (!closed) {
                log.debug("SIP mux 读循环结束: {}", e.getMessage());
            }
        } finally {
            close();
        }
    }

    /**
     * 阻塞读满指定长度。
     *
     * @param n 期望长度
     * @return 数据
     * @throws IOException 流结束
     */
    private byte[] readFully(int n) throws IOException {
        byte[] data = new byte[n];
        int offset = 0;
        while (offset < n) {
            int read = in.read(data, offset, n - offset);
            if (read == -1) {
                throw new IOException("SIP mux 连接已关闭");
            }
            offset += read;
        }
        return data;
    }

    /**
     * UUID 字符串转 16 字节（去连字符后按十六进制）。
     *
     * @param uuid UUID 字符串
     * @return 16 字节
     */
    private static byte[] uuidBytes(String uuid) {
        String hex = uuid.replace("-", "");
        byte[] data = new byte[CH_LEN];
        for (int i = 0; i < CH_LEN; i++) {
            data[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return data;
    }

    /**
     * 16 字节转 UUID 字符串（标准连字符格式）。
     *
     * @param data 16 字节
     * @return UUID 字符串
     */
    private static String uuidString(byte[] data) {
        StringBuilder hex = new StringBuilder();
        for (byte b : data) {
            hex.append(String.format("%02x", b));
        }
        String s = hex.toString();
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                + "-" + s.substring(16, 20) + "-" + s.substring(20);
    }
}
