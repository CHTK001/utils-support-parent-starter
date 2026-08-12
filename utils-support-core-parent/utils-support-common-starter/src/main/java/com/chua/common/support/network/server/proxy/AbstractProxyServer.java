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
 * @author CH
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
    protected void doStart() {
        try {
            InetSocketAddress addr = new InetSocketAddress(setting.getHost(), setting.getPort());
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(setting.isSoReuseAddr());
            serverSocket.setReceiveBufferSize(Math.max(setting.getBufferSize(), 16384));
            serverSocket.bind(addr, Math.max(setting.getBacklog(), 4096));
            // 回填实际端口（port=0 时由系统分配）
            setting.setPort(serverSocket.getLocalPort());
            running = true;
            proxyPool = Executors.newVirtualThreadPerTaskExecutor();
            // 连接限流
            int maxConn = setting.getMaxConnections();
            connectionLimiter = maxConn > 0 ? new Semaphore(maxConn) : null;
            // 多 acceptor：使用 bossThreads 控制并行 accept 线程数（默认 1，高并发可设 >1）
            int acceptors = Math.max(1, setting.getBossThreads());
            for (int i = 0; i < acceptors; i++) {
                proxyPool.submit(this::acceptLoop);
            }
            log.info("{} 启动成功：{}://{}:{} (acceptors={}, maxConn={}, backlog={})",
                    getClass().getSimpleName(), setting.getProtocol(), setting.getHost(),
                    setting.getPort(), acceptors, maxConn, Math.max(setting.getBacklog(), 4096));
        } catch (IOException e) {
            throw new RuntimeException(getClass().getSimpleName() + " 启动失败", e);
        }
    }

    @Override
    protected void doStop() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
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
                // TCP_NODELAY：禁用 Nagle 算法，减少小包延迟
                clientSocket.setTcpNoDelay(setting.isTcpNoDelay());
                // 连接限流：超出上限直接拒绝
                if (connectionLimiter != null && !connectionLimiter.tryAcquire()) {
                    log.warn("{} 连接数超限 (max={})，拒绝 {}", getClass().getSimpleName(),
                            setting.getMaxConnections(), clientSocket.getRemoteSocketAddress());
                    try { clientSocket.close(); } catch (IOException ignored) {}
                    continue;
                }
                proxyPool.submit(() -> {
                    try {
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
     * 转发缓冲区大小（32KB）。
     * <p>虚拟线程的 ThreadLocal 开销极低，适当增大 buffer 提升吞吐量。
     * 相比 8KB，大文件转发场景吞吐量提升约 2x。</p>
     */
    private static final int FORWARD_BUFFER_SIZE = 32 * 1024;

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
    protected static byte[] readBytes(InputStream in, int count) throws IOException {
        byte[] bytes = new byte[count];
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
