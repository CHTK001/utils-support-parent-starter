package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于原生 JDK {@link ServerSocket} 的 TCP 反向代理服务器。
 * <p>每个客户端连接使用虚拟线程进行双向转发；后端目标由
 * {@link ProxyTargetResolver} 解析，可对接静态路由、服务发现或自定义策略。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 1) 静态路由（单目标）
 * TcpProxyServer server = new TcpProxyServer(setting, remote -> new InetSocketAddress("127.0.0.1", 6379));
 *
 * // 2) 多目标，按客户端 IP 分流
 * TcpProxyServer server = new TcpProxyServer(setting, remote -> {
 *     if (remote.getAddress().toString().startsWith("/10")) {
 *         return new InetSocketAddress("redis-a", 6379);
 *     }
 *     return new InetSocketAddress("redis-b", 6379);
 * });
 *
 * server.addFilter(new AccessLogFilter());
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"tcp-proxy"})
public class TcpProxyServer extends AbstractServer {

    /**
     * 后端连接超时（毫秒）。
     */
    protected final int connectTimeoutMs;

    /**
     * IO 读取超时（毫秒）。
     */
    protected final int readTimeoutMs;

    /**
     * 虚拟线程池。
     */
    protected final ExecutorService proxyPool = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 后端目标解析器。
     */
    protected final ProxyTargetResolver<InetSocketAddress> targetResolver;

    /**
     * 服务运行状态。
     */
    protected final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 活跃连接计数。
     */
    protected final AtomicInteger activeConnections = new AtomicInteger(0);

    /**
     * JDK 服务端监听套接字。
     */
    protected ServerSocket serverSocket;

    /**
     * 构造 TCP 代理服务器（SPI 工厂使用）。
     * <p>注意：通过 SPI 加载时 {@link ProxyTargetResolver} 未提供，
     * 会拒绝所有连接（{@code resolve} 返回 null），调用方需自行注入。</p>
     *
     * @param setting 服务器配置
     */
    public TcpProxyServer(ServerSetting setting) {
        super(setting);
        this.targetResolver = remote -> null;
        this.connectTimeoutMs = setting.getReadTimeout();
        this.readTimeoutMs = setting.getWriteTimeout();
    }

    /**
     * 构造 TCP 代理服务器。
     *
     * @param setting        服务器配置
     * @param targetResolver 后端目标解析器
     */
    public TcpProxyServer(ServerSetting setting, ProxyTargetResolver<InetSocketAddress> targetResolver) {
        super(setting);
        this.targetResolver = targetResolver;
        this.connectTimeoutMs = setting.getReadTimeout();
        this.readTimeoutMs = setting.getWriteTimeout();
    }

    /**
     * 构造 TCP 代理服务器（使用默认超时）。
     *
     * @param setting        服务器配置
     * @param targetResolver 后端目标解析器
     * @param connectTimeoutMs 后端连接超时（毫秒）
     * @param readTimeoutMs    IO 读取超时（毫秒）
     */
    public TcpProxyServer(ServerSetting setting,
                          ProxyTargetResolver<InetSocketAddress> targetResolver,
                          int connectTimeoutMs,
                          int readTimeoutMs) {
        super(setting);
        this.targetResolver = targetResolver;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * 构造 TCP 代理服务器（固定后端地址）。
     *
     * @param setting  服务器配置
     * @param backend  固定后端地址
     */
    public TcpProxyServer(ServerSetting setting, InetSocketAddress backend) {
        this(setting, remote -> backend);
    }

    /**
     * 注册服务器过滤器。
     * <p>TCP 代理可叠加 {@link ServerFilter}，如访问日志、限流、ACL 等。</p>
     *
     * @param filter 要注册的过滤器
     * @return 当前服务器实例
     */
    @Override
    public TcpProxyServer addFilter(ServerFilter filter) {
        super.addFilter(filter);
        return this;
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
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

    @Override
    protected void doStart() {
        try {
            InetSocketAddress addr = new InetSocketAddress(setting.getHost(), setting.getPort());
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(setting.isSoReuseAddr());
            serverSocket.bind(addr, setting.getBacklog());
            // 回填实际端口（port=0 时由系统分配）
            setting.setPort(serverSocket.getLocalPort());
            running.set(true);
            proxyPool.submit(this::acceptLoop);
            log.info("TcpProxyServer 启动成功：{}://{}:{}", setting.getProtocol(), setting.getHost(), setting.getPort());
        } catch (IOException e) {
            throw new RuntimeException("TcpProxyServer 启动失败", e);
        }
    }

    @Override
    protected void doStop() {
        running.set(false);
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        log.info("TcpProxyServer 已停止：{}://{}:{}", setting.getProtocol(), setting.getHost(), setting.getPort());
    }

    /**
     * 接受连接循环。
     */
    protected void acceptLoop() {
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                try {
                    proxyPool.submit(() -> handleConnection(clientSocket));
                } catch (Exception e) {
                    log.warn("TCP 代理任务被拒绝: {}", e.getMessage());
                    try {
                        clientSocket.close();
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.error("TCP 代理接受连接异常", e);
                }
            }
        }
    }

    /**
     * 处理单个客户端连接。
     *
     * @param clientSocket 客户端套接字
     */
    protected void handleConnection(Socket clientSocket) {
        InetSocketAddress remote = (InetSocketAddress) clientSocket.getRemoteSocketAddress();
        InetSocketAddress backendAddr = targetResolver.resolve(remote);
        if (backendAddr == null) {
            log.warn("[tcp-proxy] 无法解析后端地址 for remote={}", remote);
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
            return;
        }
        activeConnections.incrementAndGet();
        try (Socket backendSocket = new Socket()) {
            backendSocket.connect(backendAddr, connectTimeoutMs);
            backendSocket.setSoTimeout(readTimeoutMs);
            clientSocket.setSoTimeout(readTimeoutMs);
            log.debug("[tcp-proxy] 代理连接建立: {} -> {}:{}",
                    remote, backendAddr.getHostString(), backendAddr.getPort());
            forwardBidirectional(clientSocket, backendSocket);
        } catch (Exception e) {
            log.debug("[tcp-proxy] 代理连接异常: {}", e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
            activeConnections.decrementAndGet();
        }
    }

    /**
     * 双向转发客户端与后端之间的数据。
     *
     * @param clientSocket 客户端套接字
     * @param backendSocket 后端套接字
     */
    protected void forwardBidirectional(Socket clientSocket, Socket backendSocket) {
        Thread c2b = Thread.ofVirtual()
                .name("tcp-proxy-c2b-" + clientSocket.getPort())
                .start(() -> {
                    try {
                        forward(clientSocket.getInputStream(), backendSocket.getOutputStream());
                    } catch (IOException e) {
                        log.debug("[tcp-proxy] 获取 c2b 流失败: {}", e.getMessage());
                    }
                });
        Thread b2c = Thread.ofVirtual()
                .name("tcp-proxy-b2c-" + clientSocket.getPort())
                .start(() -> {
                    try {
                        forward(backendSocket.getInputStream(), clientSocket.getOutputStream());
                    } catch (IOException e) {
                        log.debug("[tcp-proxy] 获取 b2c 流失败: {}", e.getMessage());
                    }
                });
        try {
            c2b.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        b2c.interrupt();
    }

    /**
     * 单向数据转发。
     *
     * @param in  源输入流
     * @param out 目标输出流
     */
    protected void forward(InputStream in, OutputStream out) {
        try {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (Exception e) {
            if (running.get()) {
                log.debug("[tcp-proxy] 转发结束: {}", e.getMessage());
            }
        }
    }
}
