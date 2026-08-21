package com.chua.common.support.network.tcp;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * 基于 JDK NIO SocketChannel 的 TCP 长度帧客户端实现。
 *
 * <p>每次 {@link #call} 新建连接、交换帧、关闭(虚拟线程并行,不阻塞载体线程),
 * 无连接池,避免池串行化建连成为高并发瓶颈。</p>
 *
 * <p>安全与健壮性约束：</p>
 * <ul>
 *   <li>连接与读操作均受配置超时约束，避免对端无响应时无限阻塞</li>
 *   <li>响应报文长度受 {@link #MAX_BODY_SIZE} 上限约束，防止恶意/异常服务端 OOM</li>
 *   <li>对端连接断开时立即抛出异常，避免 {@code read} 返回 {@code -1} 后死循环</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("jdk-tcp")
public class JdkTcpClient implements TcpClient {

    /**
     * 长度头字节数
     */
    private static final int HEADER_SIZE = 4;

    /**
     * 消息体长度上限（字节），默认 8MB
     */
    private static final int MAX_BODY_SIZE = 8 * 1024 * 1024;

    /**
     * 默认连接超时（毫秒）
     */
    private static final int DEFAULT_CONNECT_TIMEOUT = 5000;

    /**
     * 默认读超时（毫秒）
     */
    private static final int DEFAULT_READ_TIMEOUT = 10000;

    /**
     * 连接超时（毫秒）
     */
    private final int connectTimeout;

    /**
     * 读超时（毫秒）
     */
    private final int readTimeout;

    /**
     * 写缓冲区（ThreadLocal 复用，避免每请求分配 ByteBuffer）
     */
    private static final ThreadLocal<ByteBuffer> WRITE_BUFFER =
            ThreadLocal.withInitial(() -> ByteBuffer.allocate(1024));

    /**
     * 4 字节头缓冲区（ThreadLocal 复用）
     */
    private static final ThreadLocal<ByteBuffer> HEADER_BUFFER =
            ThreadLocal.withInitial(() -> ByteBuffer.allocate(HEADER_SIZE));

    /**
     * 创建 TCP 长度帧客户端(默认超时)。
     */
    public JdkTcpClient() {
        this(0, 0, 0);
    }

    /**
     * 创建 TCP 长度帧客户端。
     *
     * @param poolSize       保留参数(忽略),兼容旧构造
     * @param connectTimeout 连接超时（毫秒）
     * @param readTimeout    读超时（毫秒）
     */
    public JdkTcpClient(int poolSize, int connectTimeout, int readTimeout) {
        this.connectTimeout = connectTimeout > 0 ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        this.readTimeout = readTimeout > 0 ? readTimeout : DEFAULT_READ_TIMEOUT;
    }

    @Override
    /** 调用 */
    public byte[] call(String host, int port, byte[] request) throws Exception {
        // 每次调用新建连接、交换帧、关闭;虚拟线程并行,不阻塞载体线程,无池串行瓶颈
        SocketChannel ch = null;
        try {
            ch = SocketChannel.open();
            ch.configureBlocking(true);
            ch.socket().setReuseAddress(true);
            ch.socket().setTcpNoDelay(true);
            ch.socket().connect(new InetSocketAddress(host, port), connectTimeout);
            ch.socket().setSoTimeout(readTimeout);
            return exchange(ch, request);
        } finally {
            if (ch != null) {
                try {
                    ch.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 在已建立的连接上执行一次请求-响应交换。
     *
     * @param ch      已连接的通道
     * @param request 请求帧字节（不含长度头）
     * @return 响应帧字节（不含长度头）
     * @throws IOException 传输失败时抛出
     */
    private byte[] exchange(SocketChannel ch, byte[] request) throws IOException {
        ByteBuffer buf = WRITE_BUFFER.get();
        int needed = HEADER_SIZE + request.length;
        if (buf.capacity() < needed) {
            buf = ByteBuffer.allocate(Math.max(needed, needed * 2));
            WRITE_BUFFER.set(buf);
        } else {
            buf.clear();
        }
        buf.putInt(request.length);
        buf.put(request);
        buf.flip();
        ch.write(buf);
        ByteBuffer headerBuf = HEADER_BUFFER.get();
        headerBuf.clear();
        readFully(ch, headerBuf);
        headerBuf.flip();
        int bodyLen = headerBuf.getInt();
        if (bodyLen <= 0 || bodyLen > MAX_BODY_SIZE) {
            throw new IOException("TCP response too large: " + bodyLen);
        }
        // 直接反序列化 bodyBuf 底层数组，避免再拷贝一份 byte[]，减少热路径分配
        ByteBuffer bodyBuf = ByteBuffer.allocate(bodyLen);
        readFully(ch, bodyBuf);
        return bodyBuf.array();
    }

    /**
     * 阻塞式完整读取，带读超时；对端断开（{@code read == -1}）时立即抛异常，避免死循环。
     *
     * @param ch  套接字通道
     * @param buf 目标缓冲区
     * @throws IOException 对端关闭或读取失败时抛出
     */
    private void readFully(SocketChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            int read = ch.read(buf);
            if (read == -1) {
                throw new IOException("TCP connection closed by server");
            }
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        // 无池,无需清理
    }

    /**
     * \u5b89\u9759\u5173\u95ed\u901a\u9053\uff0c\u5ffd\u7565\u5173\u95ed\u8fc7\u7a0b\u4e2d\u7684\u5f02\u5e38\u3002
     *
     * @param ch \u901a\u9053\uff0c\u53ef\u4e3a {@code null}
     */
    private static void closeQuietly(SocketChannel ch) {
        if (ch == null) {
            return;
        }
        try {
            ch.close();
        } catch (IOException ignored) {
        }
    }
}