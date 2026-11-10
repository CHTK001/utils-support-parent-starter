// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.aio;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.impl.TcpServerRequest;
import com.chua.common.support.network.server.impl.TcpServerResponse;
import com.chua.common.support.network.server.impl.TcpFrameWriter;
import com.chua.common.support.network.tcp.TcpServer;
import com.chua.common.support.network.tcp.callback.TcpServerHandler;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 {@link AsynchronousSocketChannel} 的 TCP 服务器(Proactor/IOCP)。
 *
 * <p>两种处理模式:
 * <ul>
 *   <li>帧模式(默认):{@code setFrameHandler(bytes -> bytes)} — 每个读取块作为一个
 *       请求帧,处理器返回响应字节回写(请求-响应语义)</li>
 *   <li>管道模式:{@code setRawPipeHandler((in,out) -> {...})} — 连接建立后回调
 *       阻塞流,由业务自行读写(长连接/自定义协议);运行于每连接虚拟线程</li>
 * </ul>
 * @author CH
 * @since 2026/08/24
 */
@Slf4j
@Spi({"aio-tcp", "tcp-aio"})
public class AioTcpServer extends AbstractServer implements TcpServer {

    /**
     * 最小 backlog：高并发连接突发下避免内核队列满导致连接被拒。
     *
     * <p>内核会按 {@code somaxconn} 截断，这里给大值等价于「用平台允许的最大队列」，
     * 不预分配内存。</p>
     */
    private static final int MIN_BACKLOG = 65536;

    /**
     * 监听通道
     */
    private AsynchronousServerSocketChannel serverChannel;
    /**
     * IOCP 完成端口线程组
     */
    private AsynchronousChannelGroup group;
    /**
     * 虚拟线程 worker 池
     */
    private ExecutorService executor;

    /**
     * 帧模式处理器
     */
    private volatile TcpServerHandler frameHandler;
    /**
     * 管道模式处理器
     */
    private volatile RawPipeHandler rawPipeHandler;

    /**
     * 连接关闭统一收尾：归还基类连接预算。
     *
     * <p>AIO 的关闭路径分散在读回调、写回调、异常回调、管道模式与 stop()，
     * 逐处直接操作计数很容易漏掉基类预算（漏掉就会让活跃连接数只增不减，
     * 最终所有新连接都被 503 拒绝）。统一走本方法。</p>
     *
     * <p>本类不再单独维护活跃连接数：{@link #tryAcquireConnection()} 在 accept 成功后
     * 已经计入 {@code metrics}，重复维护一份本地计数只会两边漂移。</p>
     */
    private void releaseConnectionSlot() {
        releaseConnection();
    }


    /**
     * 异步连接句柄(纯非阻塞):同一时刻至多一个挂起读/写。
     */
    public interface AsyncConn {
        /**
         * 发起一次异步读;收到数据回调 onData(需再次 read 续读),
         * 对端关闭回调 onEof,异常回调 onError。
         */
        void read(java.util.function.BiConsumer<byte[], Integer> onData,
                  Runnable onEof,
                  java.util.function.Consumer<Throwable> onError);

        /**
         * 异步写整段字节,写完回调 onDone。
         */
        void write(byte[] data, Runnable onDone,
                   java.util.function.Consumer<Throwable> onError);

        /**
         * 关闭连接(幂等)。
         */
        void close();
    }

    /**
     * 管道模式处理器契约(纯异步)。
     */
    @FunctionalInterface
    public interface RawPipeHandler {
        /**
         * 连接建立后回调一次。
         *
         * @param conn 异步连接句柄
         */
        void handle(AsyncConn conn) throws Exception;
    }

    /**
     * 创建 AIO TCP 服务器。
     *
     * @param setting 配置
     */
    public AioTcpServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * 设置帧模式处理器(请求字节 → 响应字节)。
     *
     * @param handler 处理器
     * @return 当前实例
     */
    public AioTcpServer setFrameHandler(TcpServerHandler handler) {
        this.frameHandler = handler;
        return this;
    }

    /**
     * 注册帧模式处理器（默认模式）。
     *
     * <p>满足 {@link TcpServer} 接口约定：等价于 {@link #setFrameHandler(TcpServerHandler)}。</p>
     *
     * @param handler 处理器
     * @return 当前实例
     */
    @Override
    public TcpServer setHandler(TcpServerHandler handler) {
        return setFrameHandler(handler);
    }

    /**
     * 设置管道模式处理器(阻塞流直通业务)。
     *
     * @param handler 处理器
     * @return 当前实例
     */
    public AioTcpServer setRawPipeHandler(RawPipeHandler handler) {
        this.rawPipeHandler = handler;
        return this;
    }

    /**
     * Do开始
     */
    @Override
    protected void doStart() {
        try {
            int groupThreads = setting.getEventLoops() > 0
                    ? setting.getEventLoops() : Runtime.getRuntime().availableProcessors();
            group = AsynchronousChannelGroup.withFixedThreadPool(groupThreads, r -> {
// [P3C C1 豁免] 守护/后台线程：有独立生命周期，不适合交给通用执行器
                Thread t = new Thread(r, "aio-tcp-iocp");
                t.setDaemon(true);
                return t;
            });
            serverChannel = AsynchronousServerSocketChannel.open(group);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
            int backlog = setting.resolveBacklog(MIN_BACKLOG);
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()), backlog);
            setting.setPort(((InetSocketAddress) serverChannel.getLocalAddress()).getPort());
// [P3C C1 豁免] 场景化创建线程，需文档化
            executor = Executors.newVirtualThreadPerTaskExecutor();
            acceptLoop();
            log.info("AIO TcpServer started on {}:{} (iocpThreads={})",
                    setting.getHost(), setting.getPort(), groupThreads);
        } catch (Exception e) {
            throw new RuntimeException("AIO TcpServer 启动失败", e);
        }
    }

    /**
     * 发起异步 accept(纯回调):完成后补位续挂,连接交虚拟线程处理。
     */
    private void acceptLoop() {
        issueAccept();
    }

    /**
     * 挂起一次重叠 accept。
     */
    private void issueAccept() {
        // 不检查 running:start() 模板在 doStart 返回后才置位,首挂 accept 会因此永不发生
        if (serverChannel == null || !serverChannel.isOpen()) {
            return;
        }
        serverChannel.accept(null, new java.nio.channels.CompletionHandler<AsynchronousSocketChannel, Void>() {
            @Override
            public void completed(AsynchronousSocketChannel channel, Void attachment) {
                issueAccept();
                if (!accepting) {
                    // 优雅关闭中：不再接纳新连接（存量连接不受影响）
                    closeQuietly(channel);
                    return;
                }
                if (!tryAcquireConnection()) {
                    // 超限：直接关闭（非 HTTP 协议回 503 无意义），监控可见拒绝数
                    closeQuietly(channel);
                    return;
                }
                handleAccepted(channel);
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                if (running && serverChannel != null && serverChannel.isOpen()) {
                    issueAccept();
                }
            }
        });
    }

    /**
     * 新连接初始化并派发到虚拟线程。
     *
     * @param channel 已接入通道
     */
    private void handleAccepted(AsynchronousSocketChannel channel) {
        try {
            executor.submit(() -> {
                try {
                    channel.setOption(StandardSocketOptions.TCP_NODELAY, setting.isTcpNoDelay());
                } catch (Exception e) {
                    log.debug("[aio] 设置 socket 选项失败，已忽略: {}", e.getMessage());
                }
                // 缓冲区按配置应用（默认不配置时无系统调用）
                applyChannelSocketOptions(channel);
                if (rawPipeHandler != null) {
                    // 管道模式:连接生命周期由 AsyncConn 控制；处理器抛异常时由 close 统一收尾，
                    // 保证预算只归还一次
                    AsyncConn conn = new AsyncConnImpl(channel);
                    try {
                        rawPipeHandler.handle(conn);
                    } catch (Exception e) {
                        log.debug("AIO TcpServer 管道处理异常: {}", e.getMessage());
                        conn.close();
                    }
                    return;
                }
                try {
                    if (frameHandler != null) {
                        // 帧模式:纯异步回调链。读缓冲随连接走（见 FrameReadBuffer），
                        // 不再每次读到数据就 allocateDirect 一块新的
                        issueFrameRead(channel, newFrameReadBuffer());
                    }
                } catch (Exception e) {
                    log.debug("AIO TcpServer 连接结束: {}", e.getMessage());
                    closeQuietly(channel);
                    releaseConnectionSlot();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 停止中任务被拒：连接不会被处理，必须在这里归还预算
            closeQuietly(channel);
            releaseConnectionSlot();
        }
    }

    /**
     * 分配帧模式的连接读缓冲（direct，按 bufferSize 或 32KB 取大）。
     *
     * <p>只在连接建立时分配一次，之后整条连接复用同一块；
     * 旧实现把它放在 {@code writeFrame} 的写完成回调里，等于「每帧响应分配一块
     * 32KB direct buffer」，每帧一次堆外分配 + 一次 GC 回收，是高吞吐下最主要的
     * 内存抖动来源（direct buffer 的回收还要等 Cleaner）。</p>
     *
     * @return 新读缓冲
     */
    private ByteBuffer newFrameReadBuffer() {
        return ByteBuffer.allocateDirect(Math.max(setting.getBufferSize(), 32768));
    }
    /**
     * 发起一帧的异步读(读完成 → 处理器 → 异步写 → 续读,全程无阻塞)。
     *
     * @param channel 通道
     * @param buf     读缓冲(连接生命周期内复用)
     */
    private void issueFrameRead(AsynchronousSocketChannel channel, ByteBuffer buf) {
        if (!running || !channel.isOpen()) {
            // 停止中：关闭连接并归还预算，否则该连接的配额会永久泄漏
            closeQuietly(channel);
            releaseConnectionSlot();
            return;
        }
        buf.clear();
        channel.read(buf, null, new java.nio.channels.CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer n, Void attachment) {
                if (n == null || n < 0 || !running) {
                    closeQuietly(channel);
                    releaseConnectionSlot();
                    return;
                }
                buf.flip();
                byte[] request = new byte[buf.remaining()];
                buf.get(request);
                // URL 路由模式：走 Filter Chain；否则走旧帧式路径
                if (urlMappingFilter != null && urlMappingFilter.getFactory().routeCount() > 0) {
                    processViaFilterChain(channel, request, buf);
                    return;
                }
                if (frameHandler == null) {
                    closeQuietly(channel);
                    releaseConnectionSlot();
                    return;
                }
                byte[] response;
                try {
                    response = frameHandler.handle(request);
                } catch (Exception e) {
                    log.debug("帧处理器异常: {}", e.getMessage());
                    closeQuietly(channel);
                    releaseConnectionSlot();
                    return;
                }
                if (response == null || response.length == 0) {
                    issueFrameRead(channel, buf);
                    return;
                }
                writeFrame(channel, response, buf);
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                closeQuietly(channel);
                releaseConnectionSlot();
            }
        });
    }

    /**
     * 通过 Filter Chain 处理请求（URL 路由模式），将完整 HTTP 响应写回通道。
     *
     * @param channel 方法入参 channel
     * @param reqData 请求数据，不允许为 null
     * @param readBuf 该连接的读缓冲（写完后复用同一块续读，避免每帧 allocateDirect）
     */
    private void processViaFilterChain(AsynchronousSocketChannel channel, byte[] reqData, ByteBuffer readBuf) {
        InetSocketAddress remoteAddr = null;
        try {
            java.net.SocketAddress addr = channel.getRemoteAddress();
            if (addr instanceof InetSocketAddress isa) {
                remoteAddr = isa;
            }
        } catch (Exception ignored) {
            // NOTHING
        }
        TcpServerRequest request = new TcpServerRequest(reqData, remoteAddr, setting.getCharset(), setting.getMaxRequestSize());
        TcpServerResponse response = new TcpServerResponse();
        try {
            handleRequest(request, response);
        } catch (Exception e) {
            log.debug("帧处理器异常: {}", e.getMessage());
            closeQuietly(channel);
            releaseConnectionSlot();
            return;
        }
        if (!response.isEnded()) {
            response.end();
        }
        byte[] respFrame = response.getReadyBytes();
        if (respFrame != null && respFrame.length > 0) {
            writeFrame(channel, respFrame, readBuf);
        } else {
            writeEmptyFrame(channel, readBuf);
        }
    }

    /**
     * 写出长度帧：4 字节大端长度头 + body，写完后用同一块连接读缓冲续读。
     *
     * @param channel 方法入参 channel
     * @param data 数据，不允许为 null
     * @param readBuf 该连接的读缓冲（连接生命周期内复用，避免每帧 allocateDirect）
     */
    private void writeFrame(AsynchronousSocketChannel channel, byte[] data, ByteBuffer readBuf) {
        if (data.length > 8 * 1024 * 1024) {
            log.warn("响应过大: {} bytes", data.length);
            closeQuietly(channel);
            releaseConnectionSlot();
            return;
        }
        ByteBuffer out = ByteBuffer.wrap(TcpFrameWriter.encodeFrame(data));
        channel.write(out, null, new java.nio.channels.CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer w, Void attachment) {
                issueFrameRead(channel, readBuf);
            }
            @Override
            public void failed(Throwable exc, Void attachment) {
                closeQuietly(channel);
                releaseConnectionSlot();
            }
        });
    }

    /**
     * 写出空响应帧（200 OK + Content-Length: 0）。
     *
     * @param channel 方法入参 channel
     * @param readBuf 该连接的读缓冲，写完后复用续读
     */
    private void writeEmptyFrame(AsynchronousSocketChannel channel, ByteBuffer readBuf) {
        writeFrame(channel, TcpFrameWriter.EMPTY_RESPONSE_BYTES, readBuf);
    }

    /**
     * 异步连接句柄实现:复用一块 direct 读缓冲,单挂起读/写。
     */
    private final class AsyncConnImpl implements AsyncConn {

        /**
         * 通道
         */
        private final AsynchronousSocketChannel channel;
        /**
         * 读缓冲(懒分配)
         */
        private ByteBuffer buf;
        /**
         * 关闭标志(幂等)
         */
        private final AtomicBoolean closed = new AtomicBoolean(false);

        /**
         * Async Conn Impl
         * @param channel 参数
         * @return 返回值
        */
        AsyncConnImpl(AsynchronousSocketChannel channel) {
            this.channel = channel;
        }

        @Override
        public void read(java.util.function.BiConsumer<byte[], Integer> onData,
                         Runnable onEof,
                         java.util.function.Consumer<Throwable> onError) {
            if (closed.get()) {
                onEof.run();
                return;
            }
            if (buf == null) {
                buf = newFrameReadBuffer();
            }
            buf.clear();
            channel.read(buf, null, new java.nio.channels.CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer n, Void attachment) {
                    if (n == null || n < 0) {
                        onEof.run();
                        return;
                    }
                    buf.flip();
                    byte[] data = new byte[buf.remaining()];
                    buf.get(data);
                    onData.accept(data, n);
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    onError.accept(exc);
                }
            });
        }

        @Override
        public void write(byte[] data, Runnable onDone,
                          java.util.function.Consumer<Throwable> onError) {
            ByteBuffer src = ByteBuffer.wrap(data);
            channel.write(src, null, new java.nio.channels.CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer n, Void attachment) {
                    if (src.hasRemaining()) {
                        channel.write(src, null, this);
                        return;
                    }
                    onDone.run();
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    onError.accept(exc);
                }
            });
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeQuietly(channel);
                releaseConnectionSlot();
            }
        }
    }

    /**
     * 阻塞式 Future 读适配器(虚拟线程上零平台线程占用)。
     *
     * @param channel 通道
     * @return 输入流
     */
    public static InputStream newBlockingReader(AsynchronousSocketChannel channel) {
        return new InputStream() {
            final ByteBuffer buf = ByteBuffer.allocate(8192);
            { buf.position(buf.limit()); } // 初始为空:避免把零填充当数据

            /**
             * fill
             * @return 返回值
             * @throws Exception 异常
            */
            private int fill() throws Exception {
                buf.clear();
                Integer n = channel.read(buf).get();
                if (n == null || n < 0) {
                    return -1;
                }
                buf.flip();
                return n;
            }

            @Override
            public int read() throws java.io.IOException {
                try {
                    if (!buf.hasRemaining() && fill() < 0) {
                        return -1;
                    }
                    return buf.get() & 0xFF;
                } catch (java.io.IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new java.io.IOException(e);
                }
            }

            @Override
            public int read(byte[] b, int off, int len) throws java.io.IOException {
                try {
                    if (!buf.hasRemaining() && fill() < 0) {
                        return -1;
                    }
                    int n = Math.min(len, buf.remaining());
                    buf.get(b, off, n);
                    return n;
                } catch (java.io.IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new java.io.IOException(e);
                }
            }
        };
    }

    /**
     * 阻塞式 Future 写适配器。
     *
     * @param channel 通道
     * @return 输出流
     */
    public static OutputStream newBlockingWriter(AsynchronousSocketChannel channel) {
        return new OutputStream() {
            @Override
            public void write(int b) throws java.io.IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws java.io.IOException {
                ByteBuffer src = ByteBuffer.wrap(b, off, len);
                try {
                    while (src.hasRemaining()) {
                        Integer n = channel.write(src).get();
                        if (n == null || n < 0) {
                            throw new java.io.IOException("对端关闭");
                        }
                    }
                } catch (java.io.IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new java.io.IOException(e);
                }
            }

            @Override
            public void flush() {
                // NOTHING
            }
        };
    }

    /**
     * 至少读到 1 字节或 EOF。
     *
     * @param channel 通道
     * @param dst     目标缓冲
     * @return 读取字节数,-1 表示 EOF
     */
    static int readFully(AsynchronousSocketChannel channel, ByteBuffer dst) throws Exception {
        while (true) {
            Integer n = channel.read(dst).get();
            if (n == null || n < 0) {
                return -1;
            }
            if (n > 0) {
                return n;
            }
        }
    }

    /**
     * 静默关闭通道。
     *
     * @param channel 通道
     */
    static void closeQuietly(AsynchronousSocketChannel channel) {
        try {
            channel.close();
        } catch (Exception e) {
            log.debug("[aio] 操作失败，已忽略: {}", e.getMessage());
        }
    }

    /**
     * Do停止Accepting
     */
    @Override
    protected void doStopAccepting() {
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (Exception e) {
                log.debug("[aio] 设置 socket 选项失败，已忽略: {}", e.getMessage());
            }
        }
    }

    /**
     * 恢复接纳：{@link #doStopAccepting()} 关闭了监听通道，重开需要重新 bind。
     *
     * <p>与 {@link com.chua.common.support.network.server.nio.NioHttpServer} 保持同一语义：
     * 摘流（{@code setAccepting(false)}）后可以原地恢复，不必 stop/start 整台服务器。</p>
     */
    @Override
    protected void doStartAccepting() {
        if (group == null || executor == null) {
            return;
        }
        try {
            serverChannel = AsynchronousServerSocketChannel.open(group);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()),
                    setting.resolveBacklog(MIN_BACKLOG));
            setting.setPort(((InetSocketAddress) serverChannel.getLocalAddress()).getPort());
            issueAccept();
            log.info("AIO TcpServer 恢复接纳 {}:{}", setting.getHost(), setting.getPort());
        } catch (Exception e) {
            log.warn("AIO TcpServer 恢复接纳失败: {}", e.getMessage());
        }
    }

    /**
     * Do停止
     */
    @Override
    protected void doStop() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (group != null) {
            try {
                group.shutdownNow();
            } catch (Exception e) {
                log.debug("[aio] 设置 socket 选项失败，已忽略: {}", e.getMessage());
            }
        }
        log.info("AIO TcpServer stopped");
    }

    /**
     * 获取ProtocolType
     */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

    /**
     * 获取活跃连接数。
     *
     * @return 活跃连接数
     */
    @Override
    public int getActiveConnections() {
        return metrics.getActiveConnections();
    }}