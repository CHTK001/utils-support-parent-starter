// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
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
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * 握手队列最小长度（等价于「用平台允许的最大队列」，内核按 somaxconn 截断）。
     */
    private static final int MIN_BACKLOG = 65536;

    /**
     * 监听通道
     */
    private AsynchronousServerSocketChannel serverChannel;
    /**
     * IOCP 线程组
     */
    private AsynchronousChannelGroup group;
    /**
     * 虚拟线程池
     */
    private ExecutorService executor;
    /**
     * 固定后端目标(null 则回退 setting host/port)
     */
    private volatile InetSocketAddress target;

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

    /**
     * Do开始
     */
    @Override
    protected void doStart() {
        try {
            int threads = setting.getEventLoops() > 0
                    ? setting.getEventLoops() : Runtime.getRuntime().availableProcessors();
            group = AsynchronousChannelGroup.withFixedThreadPool(threads, r -> {
// [P3C C1 豁免] 守护/后台线程：有独立生命周期，不适合交给通用执行器
                Thread t = new Thread(r, "aio-tcpproxy-iocp");
                t.setDaemon(true);
                return t;
            });
            serverChannel = AsynchronousServerSocketChannel.open(group);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()),
                    setting.resolveBacklog(MIN_BACKLOG));
            setting.setPort(((InetSocketAddress) serverChannel.getLocalAddress()).getPort());
// [P3C C1 豁免] 场景化创建线程，需文档化
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
                if (!accepting) {
                    // 优雅关闭中：不再接纳新连接
                    closeQuietly(channel);
                    return;
                }
                if (!tryAcquireConnection()) {
                    // 超限：直接关闭（TCP 代理无法回 503），拒绝数计入 metrics
                    closeQuietly(channel);
                    return;
                }
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
        ProxySession session = new ProxySession(client);
        try {
            client.setOption(StandardSocketOptions.TCP_NODELAY, true);
        } catch (Exception e) {
            log.debug("[proxy] 操作失败，已忽略: {}", e.getMessage());
        }
        // 缓冲区按配置应用（默认不配置时无系统调用）
        applyChannelSocketOptions(client);
        InetSocketAddress addr = resolveTarget();
        AsynchronousSocketChannel backend;
        try {
            backend = AsynchronousSocketChannel.open(group);
            backend.setOption(StandardSocketOptions.TCP_NODELAY, true);
        } catch (java.io.IOException e) {
            log.debug("AIO TcpProxy 打开后端通道失败: {}", e.getMessage());
            session.close();
            return;
        }
        session.backend = backend;
        // 异步连接后端,完成后启动纯回调双向泵
        backend.connect(addr, null, new java.nio.channels.CompletionHandler<Void, Void>() {
            @Override
            public void completed(Void result, Void attachment) {
                new PipeReader(client, backend, session).start();
                new PipeReader(backend, client, session).start();
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                log.debug("AIO TcpProxy 连接后端失败: {}", exc.getMessage());
                session.close();
            }
        });
    }

    /**
     * 一条代理连接的收尾句柄：两端通道 + 幂等释放。
     *
     * <p>双向泵各有一份关闭路径，任一方向 EOF/失败都会关闭两端，另一方向的回调随后
     * 也会走到关闭分支。若每处都直接 {@code decrementAndGet()}，一条连接会被减两次，
     * 活跃数迟早变成负数，{@link #tryAcquireConnection()} 的配额随之失真
     * （表现为「明明没那么多连接却一直被拒绝」）。因此一条连接只允许释放一次。</p>
     */
    private final class ProxySession {

        /**
         * 接入通道
         */
        private final AsynchronousSocketChannel client;
        /**
         * 后端通道（连接成功后才有）
         */
        private volatile AsynchronousSocketChannel backend;
        /**
         * 已释放标志
         */
        private final AtomicBoolean released = new AtomicBoolean(false);

        /**
         * Proxy Session
         * @param client 参数
         */
        ProxySession(AsynchronousSocketChannel client) {
            this.client = client;
        }

        /**
         * 关闭两端并归还连接预算（幂等）。
         */
        void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            closeQuietly(client);
            closeQuietly(backend);
            releaseConnection();
        }
    }

    /**
     * 单向异步泵:src 读完成 → dst 写完成 → 续读,全程无阻塞。
     * 任一方向 EOF/失败即关闭两端,另一方向回调随之自然终止。
     */
    private final class PipeReader
            implements java.nio.channels.CompletionHandler<Integer, Void> {

        /**
         * 源通道
         */
        private final AsynchronousSocketChannel src;
        /**
         * 目标通道
         */
        private final AsynchronousSocketChannel dst;
        /**
         * 所属连接
         */
        private final ProxySession session;
        /**
         * 中转缓冲
         */
        private final ByteBuffer buf;

        /**
         * 创建单向泵。
         * @param dst 目标通道
         * @param src 源通道
         * @param session 所属连接
         * @return 返回值
        */
        PipeReader(AsynchronousSocketChannel src, AsynchronousSocketChannel dst, ProxySession session) {
            this.src = src;
            this.dst = dst;
            this.session = session;
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
                session.close();
            }
        }

        @Override
        public void completed(Integer n, Void attachment) {
            if (n == null || n < 0 || !running) {
                session.close();
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
                    session.close();
                }
            });
        }

        @Override
        public void failed(Throwable exc, Void attachment) {
            session.close();
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
            } catch (Exception e) {
                log.debug("[proxy] 关闭连接失败，已忽略: {}", e.getMessage());
            }
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
                log.debug("[proxy] 关闭连接失败，已忽略: {}", e.getMessage());
            }
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
                log.debug("[proxy] 关闭连接失败，已忽略: {}", e.getMessage());
            }
        }
        log.info("AIO TcpProxy stopped");
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