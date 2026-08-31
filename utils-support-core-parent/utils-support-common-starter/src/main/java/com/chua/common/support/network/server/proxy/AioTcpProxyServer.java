package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.aio.AioTcpServer;
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
 * 基于 AIO(Proactor/IOCP) 的 TCP 代理:接入连接与后端连接均为
 * AsynchronousSocketChannel,双端各一条虚拟线程做阻塞 Future 泵。
 *
 * <p>目标地址解析顺序:构造器/Setter 注入的固定目标 →
 * {@code ServerSetting.getHost()/getPort()}(作为后端地址语义)。</p>
 * @author CH
 * @since 2026/08/24
 */
@Slf4j
@Spi({"aio-tcp-proxy"})
public class AioTcpProxyServer extends AbstractServer {

    /** 监听通道 */
    private AsynchronousServerSocketChannel serverChannel;
    /** IOCP 线程组 */
    private AsynchronousChannelGroup group;
    /** 虚拟线程池 */
    private ExecutorService executor;
    /** 固定后端目标(null 则回退 setting host/port) */
    private volatile InetSocketAddress target;

    /** 活跃连接数 */
    private final AtomicInteger activeConnections = new AtomicInteger();

    /**
     * 创建 AIO TCP 代理(SPI 入口,目标取 setting host/port)。
     *
     * @param setting 配置
     */
    public AioTcpProxyServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * 设置固定后端目标。
     *
     * @param host 后端主机
     * @param port 后端端口
     * @return 当前实例
     */
    public AioTcpProxyServer setTarget(String host, int port) {
        this.target = new InetSocketAddress(host, port);
        return this;
    }

    /**
     * 解析后端地址。
     *
     * @return 后端地址
     */
    private InetSocketAddress resolveTarget() {
        if (target != null) {
            return target;
        }
        return new InetSocketAddress(setting.getHost(), setting.getPort());
    }

    @Override
    /** Do开始 */
    protected void doStart() {
        try {
            int threads = setting.getEventLoops() > 0
                    ? setting.getEventLoops() : Runtime.getRuntime().availableProcessors();
            group = AsynchronousChannelGroup.withFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "aio-tcpproxy-iocp");
                t.setDaemon(true);
                return t;
            });
            serverChannel = AsynchronousServerSocketChannel.open(group);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()),
                    Math.max(setting.getBacklog(), 65536));
            setting.setPort(((InetSocketAddress) serverChannel.getLocalAddress()).getPort());
            executor = Executors.newVirtualThreadPerTaskExecutor();
            acceptLoop();
            log.info("AIO TcpProxy started on {}:{} -> {} (iocpThreads={})",
                    setting.getHost(), setting.getPort(), resolveTarget(), threads);
        } catch (Exception e) {
            throw new RuntimeException("AIO TcpProxy 启动失败", e);
        }
    }

    /**
     * 接纳循环(纯回调):完成后补位续挂。
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
                handleClient(channel);
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
     * 单连接处理:连后端 → 双向泵。
     *
     * @param client 接入通道
     */
    private void handleClient(AsynchronousSocketChannel client) {
        activeConnections.incrementAndGet();
        try {
            client.setOption(StandardSocketOptions.TCP_NODELAY, true);
        } catch (Exception ignored) {
        }
        InetSocketAddress addr = resolveTarget();
        AsynchronousSocketChannel backend;
        try {
            backend = AsynchronousSocketChannel.open(group);
            backend.setOption(StandardSocketOptions.TCP_NODELAY, true);
        } catch (java.io.IOException e) {
            log.debug("AIO TcpProxy 打开后端通道失败: {}", e.getMessage());
            closeQuietly(client);
            activeConnections.decrementAndGet();
            return;
        }
        // 异步连接后端,完成后启动纯回调双向泵
        backend.connect(addr, null, new java.nio.channels.CompletionHandler<Void, Void>() {
            @Override
            public void completed(Void result, Void attachment) {
                new PipeReader(client, backend).start();
                new PipeReader(backend, client).start();
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                log.debug("AIO TcpProxy 连接后端失败: {}", exc.getMessage());
                closeQuietly(client);
                closeQuietly(backend);
                activeConnections.decrementAndGet();
            }
        });
    }

    /**
     * 单向异步泵:src 读完成 → dst 写完成 → 续读,全程无阻塞。
     * 任一方向 EOF/失败即关闭两端,另一方向回调随之自然终止。
     */
    private final class PipeReader
            implements java.nio.channels.CompletionHandler<Integer, Void> {

        /** 源通道 */
        private final AsynchronousSocketChannel src;
        /** 目标通道 */
        private final AsynchronousSocketChannel dst;
        /** 中转缓冲 */
        private final ByteBuffer buf;

        /**
         * 创建单向泵。
         *
         * @param src 源通道
         * @param dst 目标通道
         */
        PipeReader(AsynchronousSocketChannel src, AsynchronousSocketChannel dst) {
            this.src = src;
            this.dst = dst;
            this.buf = ByteBuffer.allocateDirect(Math.max(setting.getBufferSize(), 32768));
        }

        /**
         * 启动(首次)读。
         */
        void start() {
            if (running && src.isOpen()) {
                buf.clear();
                src.read(buf, null, this);
            } else {
                closeQuietly(src);
                closeQuietly(dst);
                activeConnections.decrementAndGet();
            }
        }

        @Override
        public void completed(Integer n, Void attachment) {
            if (n == null || n < 0 || !running) {
                closeQuietly(src);
                closeQuietly(dst);
                activeConnections.decrementAndGet();
                return;
            }
            buf.flip();
            dst.write(buf, null, new java.nio.channels.CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer w, Void attachment) {
                    if (buf.hasRemaining()) {
                        dst.write(buf, null, this);
                        return;
                    }
                    start();
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    closeQuietly(src);
                    closeQuietly(dst);
                    activeConnections.decrementAndGet();
                }
            });
        }

        @Override
        public void failed(Throwable exc, Void attachment) {
            closeQuietly(src);
            closeQuietly(dst);
            activeConnections.decrementAndGet();
        }
    }

    /**
     * 静默关闭通道。
     *
     * @param ch 通道
     */
    private static void closeQuietly(AsynchronousSocketChannel ch) {
        if (ch != null) {
            try {
                ch.close();
            } catch (Exception ignored) {
            }
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
        log.info("AIO TcpProxy stopped");
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