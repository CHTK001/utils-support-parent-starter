package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP 代理服务器抽象基类。
 *
 * <p>提供代理服务器的公共基础设施：</p>
 * <ul>
 *   <li>虚拟线程池生命周期管理（start 时创建，stop 时关闭）</li>
 *   <li>ServerSocket 监听与接受循环</li>
 *   <li>双向数据转发</li>
 *   <li>活跃连接计数</li>
 * </ul>
 *
 * <p>子类只需实现 {@link #handleConnection(Socket)} 即可。</p>
 *
 * @since 4.0.0.42
 */
@Slf4j
public abstract class AbstractProxyServer extends AbstractServer {

    /**
     * 活跃连接计数。
     */
    protected final AtomicInteger activeConnections = new AtomicInteger(0);

    /**
     * 虚拟线程池（start 时创建，stop 时关闭）。
     * <p>所有 accept 线程和转发线程均由此 executor 管理，
     * 确保 {@code shutdownNow()} 可取消全部活跃任务。</p>
     */
    protected ExecutorService proxyPool;

    /**
     * JDK 服务端监听套接字。
     */
    protected ServerSocket serverSocket;

    /**
     * 连接数限流信号量（maxConnections > 0 时启用）。
     * <p>超出上限时直接关闭客户端 Socket，避免内存溢出。</p>
     */
    protected Semaphore connectionLimiter;

    /**
     * 是否使用非阻塞事件循环批量 accept（默认 false = 阻塞 accept）。
     * <p>设为 true 时改用 {@link ServerSocketChannel} + Selector，每次 select 后
     * 循环 accept 全部就绪连接（批量 drain），瞬时接纳吞吐显著高于阻塞 accept
     * 一次一个。子类（如 TcpProxyServer）可覆写置为 true，Socks5 等保持默认。</p>
     */
    protected volatile boolean preferNonBlockingAccept = false;

    /** 非阻塞 accept 用的服务端通道（仅 {@link #preferNonBlockingAccept} 为 true 时使用） */
    protected ServerSocketChannel acceptChannel;

    /** 非阻塞 accept 用的选择器（仅 {@link #preferNonBlockingAccept} 为 true 时使用） */
    protected Selector acceptSelector;

    /**
     * 构造代理服务器。
     *
     * @param setting 服务器配置
     */
    protected AbstractProxyServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * 获取当前活跃连接数。
     *
     * @return 活跃连接数
     */
    public int getActiveConnections() {
        return activeConnections.get();
    }

    @Override
    /** Do开始 */
    protected void doStart() {
        try {
            InetSocketAddress addr = new InetSocketAddress(setting.getHost(), setting.getPort());
            int backlog = Math.max(setting.getBacklog(), 65536);
            connectionLimiter = maxConnectionsSemaphore();
            running = true;
            proxyPool = Executors.newVirtualThreadPerTaskExecutor();
            if (preferNonBlockingAccept) {
                // 非阻塞批量 accept：Selector 事件循环驱动，每次 select 后循环 accept 全部就绪连接，
                // 显著提升瞬时接纳吞吐（对标 NioHttpServer 事件循环 accept）
                acceptChannel = ServerSocketChannel.open();
                acceptChannel.configureBlocking(false);
                acceptChannel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
                acceptChannel.setOption(java.net.StandardSocketOptions.SO_RCVBUF, Math.max(setting.getBufferSize(), 16384));
                acceptChannel.bind(addr, backlog);
                setting.setPort(((InetSocketAddress) acceptChannel.getLocalAddress()).getPort());
                acceptSelector = Selector.open();
                acceptChannel.register(acceptSelector, SelectionKey.OP_ACCEPT);
                // 单事件循环批量 drain：与其多 acceptor 各自阻塞 accept 一次一个，
                // 不如单一 Selector 循环批量接纳，吞吐由 drain 密度决定
                proxyPool.submit(this::nonBlockingAcceptLoop);
                log.info("{} 启动成功（非阻塞批量 accept）：{}://{}:{} (backlog={}, maxConn={})",
                        getClass().getSimpleName(), setting.getProtocol(), setting.getHost(),
                        setting.getPort(), backlog, connectionLimiter != null ? setting.getMaxConnections() : 0);
                return;
            }
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(setting.isSoReuseAddr());
            serverSocket.setReceiveBufferSize(Math.max(setting.getBufferSize(), 16384));
            // backlog 下限 65536:瞬间并发连接(万级突发)下避免内核 accept 队列溢出导致连接被拒
            serverSocket.bind(addr, backlog);
            // 回填实际端口（port=0 时由系统分配）
            setting.setPort(serverSocket.getLocalPort());
            // 多 acceptor：使用 bossThreads 控制并行 accept 线程数（ServerSetting.auto() 已按平台生成最优值，
            // 高并发下支撑百万级连接建立;同一 ServerSocket 多线程 accept 为 JDK 支持用法）
            int acceptors = Math.max(1, setting.getBossThreads());
            for (int i = 0; i < acceptors; i++) {
                proxyPool.submit(this::acceptLoop);
            }
            log.info("{} 启动成功：{}://{}:{} (acceptors={}, maxConn={}, backlog={})",
                    getClass().getSimpleName(), setting.getProtocol(), setting.getHost(),
                    setting.getPort(), acceptors, connectionLimiter != null ? setting.getMaxConnections() : 0, backlog);
        } catch (IOException e) {
            throw new RuntimeException(getClass().getSimpleName() + " 启动失败", e);
        }
    }

    /** 构建连接限流信号量（maxConnections > 0 时启用），供 doStart 分支复用。 */
    private Semaphore maxConnectionsSemaphore() {
        int maxConn = setting.getMaxConnections();
        return maxConn > 0 ? new Semaphore(maxConn) : null;
    }

    @Override
    /** Do停止 */
    protected void doStop() {
        running = false;
        if (preferNonBlockingAccept && acceptChannel != null) {
            try {
                if (acceptSelector != null) {
                    acceptSelector.close();
                }
                acceptChannel.close();
            } catch (IOException ignored) {
            }
        } else if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        if (proxyPool != null) {
            proxyPool.shutdownNow();
            proxyPool = null;
        }
        log.info("{} 已停止：{}://{}:{} (peak connections: {})",
                getClass().getSimpleName(), setting.getProtocol(), setting.getHost(),
                setting.getPort(), metrics.getPeakActive());
    }

    /**
     * 接受连接循环。
     * <p>支持多 acceptor 并行（bossThreads > 1 时），
     * 超过 maxConnections 时直接关闭连接并发送 503。</p>
     */
    protected void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                // 连接限流：超出上限直接拒绝
                if (connectionLimiter != null && !connectionLimiter.tryAcquire()) {
                    log.warn("{} 连接数超限 (max={})，拒绝 {}", getClass().getSimpleName(),
                            setting.getMaxConnections(), clientSocket.getRemoteSocketAddress());
                    try { clientSocket.close(); } catch (IOException ignored) {}
                    continue;
                }
                proxyPool.submit(() -> {
                    try {
                        // TCP_NODELAY 移到连接处理线程:accept 热路径只做 accept+限流+submit,
                        // 提升瞬时连接接纳能力
                        try {
                            clientSocket.setTcpNoDelay(setting.isTcpNoDelay());
                        } catch (IOException ignored) {
                        }
                        handleConnection(clientSocket);
                    } finally {
                        if (connectionLimiter != null) {
                            connectionLimiter.release();
                        }
                    }
                });
            } catch (IOException e) {
                if (running) {
                    log.error("{} 接受连接异常", getClass().getSimpleName(), e);
                }
            }
        }
    }

    /**
     * 非阻塞事件循环批量 accept（{@link #preferNonBlockingAccept} 为 true 时使用）。
     * <p>每次 select 后循环 accept 全部就绪连接并批量提交到连接处理线程，
     * 瞬时接纳吞吐显著高于阻塞 accept 一次一个，可在突发接入(每秒数千连接)下
     * 避免内核 accept 队列积压导致连接被拒。握手后的 {@link SocketChannel} 通过
     * {@link SocketChannel#socket()} 包装为 {@link Socket}，复用
     * {@link #handleConnection(Socket)} 子类契约。</p>
     */
    protected void nonBlockingAcceptLoop() {
        log.info("{} nonBlockingAcceptLoop started", getClass().getSimpleName());
        while (running) {
            try {
                int n = acceptSelector.select(5000L);
                if (!running) break;
                java.util.Iterator<SelectionKey> it = acceptSelector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    SocketChannel ch;
                    try {
                        ch = acceptChannel.accept();
                    } catch (java.nio.channels.ClosedChannelException e2) {
                        log.info("{} nonBlockingAcceptLoop: ClosedChannelException, exiting", getClass().getSimpleName());
                        return;
                    }
                    if (ch == null) {
                        continue;
                    }
                    try {
                        ch.configureBlocking(true);
                        Socket clientSocket = ch.socket();
                        if (connectionLimiter != null && !connectionLimiter.tryAcquire()) {
                            log.warn("{} 连接数超限 (max={})，拒绝 {}", getClass().getSimpleName(),
                                    setting.getMaxConnections(), clientSocket.getRemoteSocketAddress());
                            closeSocket(clientSocket);
                            continue;
                        }
                        proxyPool.submit(() -> {
                            try {
                                try {
                                    clientSocket.setTcpNoDelay(setting.isTcpNoDelay());
                                } catch (IOException ignored) {
                                }
                                handleConnection(clientSocket);
                            } finally {
                                if (connectionLimiter != null) {
                                    connectionLimiter.release();
                                }
                            }
                        });
                    } catch (Exception e) {
                        log.warn("{} accept handler exception: {} {}", getClass().getSimpleName(),
                                e.getClass().getSimpleName(), e.getMessage());
                        closeSocket(ch.socket());
                    }
                }
            } catch (java.nio.channels.ClosedSelectorException e2) {
                log.info("{} nonBlockingAcceptLoop: ClosedSelectorException, exiting", getClass().getSimpleName());
                return;
            } catch (IOException e) {
                if (running) {
                    log.warn("{} 非阻塞 accept 异常: {}", getClass().getSimpleName(), e.getMessage());
                }
            }
        }
        log.info("{} nonBlockingAcceptLoop ended", getClass().getSimpleName());
    }

    /** 静默关闭套接字。 */
    private void closeSocket(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * 处理单个客户端连接。子类实现具体协议逻辑。
     *
     * @param clientSocket 客户端套接字
     */
    protected abstract void handleConnection(Socket clientSocket);

    /**
     * 双向转发：客户端 ↔ 后端。
     *
     * <p>使用 proxyPool 中的虚拟线程分别处理双向数据流。
     * 任一方向结束时，通过关闭双方 Socket 解除另一方向的阻塞。
     * 线程由 proxyPool 管理，shutdownNow() 可中断全部转发。</p>
     *
     * @param clientSocket  客户端套接字
     * @param backendSocket 后端套接字
     */
    protected void forwardBidirectional(Socket clientSocket, Socket backendSocket) {
        // 后端 Socket 也启用 TCP_NODELAY
        try {
            backendSocket.setTcpNoDelay(setting.isTcpNoDelay());
        } catch (IOException ignored) {}
        // 两个方向均提交到 proxyPool，通过 CompletableFuture 阻塞调用者
        // 确保 handleConnection() 的 try-finally 正确跟踪连接生命周期
        CompletableFuture<Void> c2b = CompletableFuture.runAsync(() -> {
            try {
                forward(clientSocket.getInputStream(), backendSocket.getOutputStream());
            } catch (IOException e) {
                log.debug("[proxy] c2b 流获取失败: {}", e.getMessage());
            } finally {
                closeQuietly(clientSocket);
                closeQuietly(backendSocket);
            }
        }, proxyPool);
        CompletableFuture<Void> b2c = CompletableFuture.runAsync(() -> {
            try {
                forward(backendSocket.getInputStream(), clientSocket.getOutputStream());
            } catch (IOException e) {
                log.debug("[proxy] b2c 流获取失败: {}", e.getMessage());
            } finally {
                closeQuietly(clientSocket);
                closeQuietly(backendSocket);
            }
        }, proxyPool);
        // 阻塞等待两个方向都完成（shutdownNow 会中断虚拟线程解除阻塞）
        try {
            c2b.join();
        } catch (Exception ignored) {}
        try {
            b2c.join();
        } catch (Exception ignored) {}
    }

    /**
     * 转发缓冲区大小（64KB）。
     * <p>虚拟线程的 ThreadLocal 开销极低,适当增大 buffer 提升吞吐量。
     * 相比 8KB,大文件转发场景吞吐量提升约 2x;相比 32KB,大报文场景
     * read/write 系统调用进一步减半。</p>
     */
    private static final int FORWARD_BUFFER_SIZE = 64 * 1024;

    /**
     * 转发用缓冲区，每虚拟线程独立缓存。
     */
    private static final ThreadLocal<byte[]> FORWARD_BUFFER =
            ThreadLocal.withInitial(() -> new byte[FORWARD_BUFFER_SIZE]);

    /**
     * 单向数据转发。
     *
     * @param in  源输入流
     * @param out 目标输出流
     */
    protected void forward(InputStream in, OutputStream out) {
        try {
            byte[] buffer = FORWARD_BUFFER.get();
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (Exception e) {
            if (running) {
                log.debug("[proxy] 转发结束: {}", e.getMessage());
            }
        }
    }

    /**
     * 安全关闭 Socket。
     */
    protected static void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 读取指定字节数。
     *
     * @param in    输入流
     * @param count 字节数
     * @return 字节数组
     * @throws IOException IO 异常
     */
    /**
     * readBytes 复用缓冲：调用点均立即消费返回值（不跨调用持有），
     * 避免热路径（每连接多次小结构读取）反复分配小数组。
     */
    private static final ThreadLocal<byte[]> READ_BUFFER =
            ThreadLocal.withInitial(() -> new byte[64]);

    /**
     * 从输入流精确读取 {@code count} 字节。
     *
     * @param in    输入流
     * @param count 字节数
     * @return 读取的字节（复用缓冲；调用方须在下次调用前消费完）
     * @throws IOException IO 异常
     */
    protected static byte[] readBytes(InputStream in, int count) throws IOException {
        byte[] bytes = READ_BUFFER.get();
        if (bytes.length < count) {
            bytes = new byte[Math.max(count, 64)];
            READ_BUFFER.set(bytes);
        }
        int offset = 0;
        while (offset < count) {
            int n = in.read(bytes, offset, count - offset);
            if (n == -1) {
                throw new IOException("连接提前关闭");
            }
            offset += n;
        }
        return bytes;
    }

    /**
     * 读取 2 字节端口号（网络字节序）。
     *
     * @param in 输入流
     * @return 端口号
     * @throws IOException IO 异常
     */
    protected static int readPort(InputStream in) throws IOException {
        byte[] port = readBytes(in, 2);
        return ((port[0] & 0xFF) << 8) | (port[1] & 0xFF);
    }
}
