package com.chua.common.support.network.tcp;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 基于 JDK NIO SocketChannel 的 TCP 长度帧客户端实现。
 *
 * <p>每个实例维护独立的虚拟线程池,每次 {@link #call} 通过池提交连接任务,
 * 一个连接一个虚拟线程,线程用完即回收,无连接池管理开销。</p>
 *
 * @author CH
 * @since 4.0.0.42
*/
@Slf4j
@Spi("jdk-tcp")
public class JdkTcpClient implements TcpClient {

    private static final int HEADER_SIZE = 4;
    private static final int MAX_BODY_SIZE = 8 * 1024 * 1024;
    private static final int DEFAULT_CONNECT_TIMEOUT = 5000;
    private static final int DEFAULT_READ_TIMEOUT = 10000;

    private final int connectTimeout;
    private final int readTimeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private static final ThreadLocal<ByteBuffer> WRITE_BUFFER =
            ThreadLocal.withInitial(() -> ByteBuffer.allocate(1024));
    private static final ThreadLocal<ByteBuffer> HEADER_BUFFER =
            ThreadLocal.withInitial(() -> ByteBuffer.allocate(HEADER_SIZE));

    /**
     * 构造方法，创建 JdkTcp客户端 实例。
     */
    public JdkTcpClient() {
        this(0, 0, 0);
    }

    /**
     * 构造方法，创建 JdkTcp客户端 实例。
     *
     * @param poolSize pool大小，不允许为 null
     * @param connectTimeout connect超时时间，不允许为 null
     * @param readTimeout 读取超时时间，不允许为 null
     */
    public JdkTcpClient(int poolSize, int connectTimeout, int readTimeout) {
        this.connectTimeout = connectTimeout > 0 ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        this.readTimeout = readTimeout > 0 ? readTimeout : DEFAULT_READ_TIMEOUT;
    }

    @Override
    public byte[] call(String host, int port, byte[] request) throws Exception {
        // 直接在本线程(已由调用方虚拟线程池管理)执行连接/交换/关闭,
        // 实例 executor 仅用于生命周期管理,不包装 call 避免额外异步开销
        SocketChannel ch = null;
        try {
            ch = SocketChannel.open();
            ch.configureBlocking(true);
            ch.socket().setReuseAddress(true);
            ch.socket().setTcpNoDelay(true);
            ch.connect(new InetSocketAddress(host, port));
            ch.socket().setSoTimeout(readTimeout);
            return exchange(ch, request);
        } finally {
            WRITE_BUFFER.remove();
            HEADER_BUFFER.remove();
            if (ch != null) {
                try {
                    ch.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * exchange。
     *
     * @param ch 方法入参 ch
     * @param request 请求，不允许为 null
     * @return 结果值
     * @throws IOException 当执行过程不满足前置条件时
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
        ByteBuffer bodyBuf = ByteBuffer.allocate(bodyLen);
        readFully(ch, bodyBuf);
        return bodyBuf.array();
    }

    /**
     * 读取Fully。
     *
     * @param ch 方法入参 ch
     * @param buf 方法入参 buf
     * @throws IOException 当执行过程不满足前置条件时
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
    public void close() {
        executor.shutdownNow();
    }
}
