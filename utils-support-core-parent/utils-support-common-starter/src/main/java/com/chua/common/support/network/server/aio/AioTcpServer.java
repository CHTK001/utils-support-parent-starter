package com.chua.common.support.network.server.aio;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.tcp.TcpServer;
import com.chua.common.support.network.tcp.callback.TcpServerHandler;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** 监听通道 */
    private AsynchronousServerSocketChannel serverChannel;
    /** IOCP 完成端口线程组 */
    private AsynchronousChannelGroup group;
    /** 虚拟线程 worker 池 */
    private ExecutorService executor;

    /** 帧模式处理器 */
    private volatile TcpServerHandler frameHandler;
    /** 管道模式处理器 */
    private volatile RawPipeHandler rawPipeHandler;

    /** 活跃连接数 */
    private final AtomicInteger activeConnections = new AtomicInteger();

    /**
     * 管道模式处理器契约。
     */
    @FunctionalInterface
    public interface RawPipeHandler {
        /**
         * 连接建立后回调(每连接虚拟线程上执行,阻塞读写安全)。
         *
         * @param in  输入流
         * @param out 输出流
         */
        void handle(InputStream in, OutputStream out) throws Exception;
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

    @Override
    /** Do开始 */
    protected void doStart() {
        try {
            int groupThreads = setting.getEventLoops() > 0
                    ? setting.getEventLoops() : Runtime.getRuntime().availableProcessors();
            group = AsynchronousChannelGroup.withFixedThreadPool(groupThreads, r -> {
                Thread t = new Thread(r, "aio-tcp-iocp");
                t.setDaemon(true);
                return t;
            });
            serverChannel = AsynchronousServerSocketChannel.open(group);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
            int backlog = Math.max(setting.getBacklog(), 65536);
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()), backlog);
            setting.setPort(((InetSocketAddress) serverChannel.getLocalAddress()).getPort());
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
        if (!running || serverChannel == null || !serverChannel.isOpen()) {
            return;
        }
        serverChannel.accept(null, new java.nio.channels.CompletionHandler<AsynchronousSocketChannel, Void>() {
            @Override
            public void completed(AsynchronousSocketChannel channel, Void attachment) {
                issueAccept();
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
        activeConnections.incrementAndGet();
        executor.submit(() -> {
            try {
                channel.setOption(StandardSocketOptions.TCP_NODELAY, setting.isTcpNoDelay());
            } catch (Exception ignored) {
            }
            try {
                if (rawPipeHandler != null) {
                    // 管道模式:显式标注为虚拟线程阻塞语义(业务自选)
                    rawPipeHandler.handle(newBlockingReader(channel), newBlockingWriter(channel));
                } else if (frameHandler != null) {
                    // 帧模式:纯异步回调链
                    issueFrameRead(channel, ByteBuffer.allocateDirect(
                            Math.max(setting.getBufferSize(), 32768)));
                }
            } catch (Exception e) {
                log.debug("AIO TcpServer 连接结束: {}", e.getMessage());
            } finally {
                closeQuietly(channel);
                activeConnections.decrementAndGet();
            }
        });
    }

    /**
     * 发起一帧的异步读(读完成 → 处理器 → 异步写 → 续读,全程无阻塞)。
     *
     * @param channel 通道
     * @param buf     读缓冲(连接生命周期内复用)
     */
    private void issueFrameRead(AsynchronousSocketChannel channel, ByteBuffer buf) {
        if (!running || !channel.isOpen()) {
            closeQuietly(channel);
            return;
        }
        buf.clear();
        channel.read(buf, null, new java.nio.channels.CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer n, Void attachment) {
                if (n == null || n < 0 || !running) {
                    closeQuietly(channel);
                    return;
                }
                buf.flip();
                byte[] request = new byte[buf.remaining()];
                buf.get(request);
                byte[] response;
                try {
                    response = frameHandler.handle(request);
                } catch (Exception e) {
                    log.debug("帧处理器异常: {}", e.getMessage());
                    closeQuietly(channel);
                    return;
                }
                if (response == null || response.length == 0) {
                    issueFrameRead(channel, buf);
                    return;
                }
                ByteBuffer out = ByteBuffer.wrap(response);
                channel.write(out, null, new java.nio.channels.CompletionHandler<Integer, Void>() {
                    @Override
                    public void completed(Integer w, Void attachment) {
                        if (out.hasRemaining()) {
                            channel.write(out, null, this);
                            return;
                        }
                        issueFrameRead(channel, buf);
                    }

                    @Override
                    public void failed(Throwable exc, Void attachment) {
                        closeQuietly(channel);
                    }
                });
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                closeQuietly(channel);
            }
        });
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
        } catch (Exception ignored) {
        }
    }

    @Override
    /** Do停止Accepting */
    protected void doStopAccepting() {
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    /** Do停止 */
    protected void doStop() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (group != null) {
            try {
                group.shutdownNow();
            } catch (Exception ignored) {
            }
        }
        log.info("AIO TcpServer stopped");
    }

    @Override
    /** 获取ProtocolType */
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

    /**
     * 获取活跃连接数。
     *
     * @return 活跃连接数
     */
    public int getActiveConnections() {
        return activeConnections.get();
    }
}