package com.chua.common.support.network.tcp;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 JDK NIO SocketChannel 的 TCP 长度帧客户端实现。
 *
 * <p>维护端点级连接池：借用即独占、归还才可用，同一连接同一时刻只有一个在途请求，
 * 保证服务端异步写响应时不会发生交错；连接失效时关闭并放回建连额度。</p>
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
     * 默认每端点连接数
     */
    private static final int DEFAULT_POOL_SIZE = 4;

    /**
     * 连接超时（毫秒）
     */
    private final int connectTimeout;

    /**
     * 读超时（毫秒）
     */
    private final int readTimeout;

    /**
     * 每个端点复用连接数（连接池大小）
     */
    private final int poolSize;

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
     * 端点地址 → 连接池；复用长连接避免高并发下反复建连导致 Windows 临时端口耗尽
     */
    private final Map<String, PooledConnections> pools = new ConcurrentHashMap<>();

    /**
     * 创建 TCP 长度帧客户端。
     *
     * @param poolSize       每个端点复用连接数
     * @param connectTimeout 连接超时（毫秒）
     * @param readTimeout    读超时（毫秒）
     */
    public JdkTcpClient(int poolSize, int connectTimeout, int readTimeout) {
        this.poolSize = poolSize > 0 ? poolSize : DEFAULT_POOL_SIZE;
        this.connectTimeout = connectTimeout > 0 ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        this.readTimeout = readTimeout > 0 ? readTimeout : DEFAULT_READ_TIMEOUT;
    }

    @Override
    /** 调用 */
    public byte[] call(String host, int port, byte[] request) throws Exception {
        String addr = host + ":" + port;
        // 复用长连接避免高并发下反复建连耗尽 Windows 临时端口；先借用再归还
        PooledConnections pool = pools.computeIfAbsent(addr,
                a -> new PooledConnections(poolSize, connectTimeout, readTimeout));
        SocketChannel ch = pool.borrow(host, port);
        boolean usable = false;
        try {
            byte[] result = exchange(ch, request);
            usable = true;
            return result;
        } finally {
            pool.recycle(ch, usable);
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
        pools.values().forEach(PooledConnections::closeAll);
        pools.clear();
    }

    /**
     * 端点级连接池：按 {@code capacity} 上限复用长连接。
     *
     * <p>设计要点：借用即独占，归还才可用——同一连接同一时刻只有一个在途请求，
     * 保证服务端异步 worker 写响应时不会发生交错；连接失效时关闭并放回建连额度。</p>
     */
    private static final class PooledConnections {

        /**
         * 空闲连接队列（容量即连接数上限）
         */
        private final ArrayBlockingQueue<SocketChannel> idle;

        /**
         * 已创建连接数（含借用中与空闲中）
         */
        private final AtomicInteger created = new AtomicInteger();

        /**
         * 连接数上限
         */
        private final int capacity;

        /**
         * 连接超时（毫秒），建连与等待空闲连接共用
         */
        private final int connectTimeout;

        /**
         * 读超时（毫秒）
         */
        private final int readTimeout;

        /**
         * 创建端点级连接池。
         *
         * @param capacity       连接数上限
         * @param connectTimeout 连接超时（毫秒）
         * @param readTimeout    读超时（毫秒）
         */
        PooledConnections(int capacity, int connectTimeout, int readTimeout) {
            this.capacity = Math.max(capacity, 1);
            this.idle = new ArrayBlockingQueue<>(this.capacity);
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
        }

        /**
         * 借用一个可用连接：优先取空闲队列，无空闲且未达上限时新建，否则等待归还。
         *
         * @param host 服务端主机
         * @param port 服务端端口
         * @return 可用的已连接通道（借用方独占，完成后必须 {@link #recycle}）
         * @throws IOException 建连失败或等待超时时抛出
         */
        SocketChannel borrow(String host, int port) throws IOException {
            for (;;) {
                SocketChannel ch = idle.poll();
                if (ch != null) {
                    if (ch.isOpen() && ch.isConnected()) {
                        return ch;
                    }
                    closeQuietly(ch);
                    created.decrementAndGet();
                    continue;
                }
                int cur = created.get();
                if (cur < capacity && created.compareAndSet(cur, cur + 1)) {
                    try {
                        return openChannel(host, port);
                    } catch (IOException e) {
                        created.decrementAndGet();
                        throw e;
                    }
                }
                // 全部连接均被占用，等待有连接被归还
                try {
                    SocketChannel waited = idle.poll(connectTimeout, TimeUnit.MILLISECONDS);
                    if (waited != null) {
                        if (waited.isOpen() && waited.isConnected()) {
                            return waited;
                        }
                        closeQuietly(waited);
                        created.decrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for pooled connection", e);
                }
            }
        }

        /**
         * 归还连接：仍可用则放回空闲队列，否则关闭并释放建连额度。
         *
         * @param ch     借用的通道
         * @param usable 连接是否仍可用（本请求是否成功交换）
         */
        void recycle(SocketChannel ch, boolean usable) {
            if (usable && idle.offer(ch)) {
                return;
            }
            closeQuietly(ch);
            created.decrementAndGet();
        }

        /**
         * 关闭池内全部空闲连接（借用中的由调用方自行归还后关闭）。
         */
        void closeAll() {
            SocketChannel ch;
            while ((ch = idle.poll()) != null) {
                closeQuietly(ch);
                created.decrementAndGet();
            }
        }

        /**
         * 新建并连接一个通道：非阻塞探测完成连接（带超时），随后切回阻塞并设置读超时。
         *
         * @param host 服务端主机
         * @param port 服务端端口
         * @return 已连接通道
         * @throws IOException 建连失败或超时时抛出
         */
        private SocketChannel openChannel(String host, int port) throws IOException {
            SocketChannel ch = SocketChannel.open();
            try {
                ch.configureBlocking(true);
                Socket socket = ch.socket();
                socket.setSoTimeout(readTimeout);
                SocketAddress target = new InetSocketAddress(host, port);
                // 阻塞模式下无法直接给 SocketChannel.connect 传超时，先切非阻塞探测再切回阻塞
                ch.configureBlocking(false);
                boolean connected = ch.connect(target);
                if (!connected) {
                    long deadline = System.currentTimeMillis() + connectTimeout;
                    while (!ch.finishConnect()) {
                        if (System.currentTimeMillis() > deadline) {
                            throw new SocketTimeoutException("TCP connect timeout: " + host + ":" + port);
                        }
                        Thread.sleep(10);
                    }
                }
                ch.configureBlocking(true);
                return ch;
            } catch (IOException | RuntimeException e) {
                closeQuietly(ch);
                throw e instanceof IOException ioe ? ioe
                        : new IOException("Failed to connect " + host + ":" + port, e);
            } catch (InterruptedException e) {
                closeQuietly(ch);
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while connecting " + host + ":" + port, e);
            }
        }

        /**
         * 安静关闭通道，忽略关闭过程中的异常。
         *
         * @param ch 通道，可为 {@code null}
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
}