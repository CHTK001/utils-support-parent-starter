package com.chua.common.support.network.protocol.server.impl;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.protocol.ServerSetting;
import com.chua.common.support.network.protocol.filter.ServletFilter;
import com.chua.common.support.network.protocol.request.BadServletResponse;
import com.chua.common.support.network.protocol.request.ProxyServletRequest;
import com.chua.common.support.network.protocol.server.AbstractProtocolServer;
import com.chua.common.support.network.protocol.server.ProtocolServer;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 基于 NIO 的 TCP 代理服务器
 * <p>
 * 纯 JDK 实现，提供 TCP 端口转发功能。
 * 优先尝试通过 ServiceProvider 加载高性能实现（如 Netty），
 * 失败时降级到本地 NIO 实现。
 *
 * @author CH
 * @since 2025/12/29
 */
@Slf4j
@Spi({"tcp-proxy", "tcp-proxy-server"})
@SpiDescribe("TCP代理服务器")
public class TcpProxyServer extends AbstractProtocolServer {

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private volatile boolean running = false;
    private ExecutorService workerPool;
    private Thread acceptThread;

    /**
     * 运行模式: netty, nio, etc
     */
    private String runMode = "unknown";

    /**
     * 委托的代理服务器
     */
    private ProtocolServer delegateServer;


    // 连接统计
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong connectionIdCounter = new AtomicLong(0);

    // 高并发配置
    private int effectiveWorkerThreads;
    private int effectiveBufferSize;
    private int effectiveMaxConnections;

    // 目标服务器配置
    private String targetHost;
    private int targetPort;

    /**
     * 是否有过滤器
     */
    private boolean hasFilters = false;

    public TcpProxyServer(ServerSetting serverSetting) {
        super(serverSetting);
    }

    @Override
    protected void doStart() throws Exception {
        // 解析目标服务器配置
        parseTargetConfig();

        // 检查是否有过滤器
        List<ServletFilter> filters = getFilters();
        hasFilters = filters != null && !filters.isEmpty();

        // 降级到 ServiceProvider 自动查找实现
        if (tryLoadServiceProviderProxy()) {
            log.info("TCP代理服务器启动 [{}] - {}:{} -> {}:{}",
                    runMode, serverSetting.getHost(), serverSetting.getPort(), targetHost, targetPort);
            return;
        }

        throw new IllegalStateException("TCP代理服务器启动失败: 无可用实现");
    }

    /**
     * 解析目标服务器配置
     */
    private void parseTargetConfig() {
        // 从 serverSetting 获取目标配置
        // 支持多种配置方式: targetHost/targetPort 或 target 属性
        targetHost = serverSetting.getTargetHost();
        targetPort = serverSetting.getTargetPort();

        if (targetHost == null || targetHost.isEmpty()) {
            // 尝试从 target 属性解析 (格式: host:port)
            String target = serverSetting.getTarget();
            if (target != null && !target.isEmpty()) {
                String[] parts = target.split(":");
                if (parts.length >= 2) {
                    targetHost = parts[0];
                    targetPort = Integer.parseInt(parts[1]);
                }
            }
        }

        if (targetHost == null || targetHost.isEmpty() || targetPort <= 0) {
            throw new IllegalArgumentException("TCP代理需要配置目标服务器: targetHost/targetPort 或 target");
        }
    }


    /**
     * 尝试通过 ServiceProvider 加载代理实现
     */
    private boolean tryLoadServiceProviderProxy() {
        try {
            delegateServer = ProtocolServer.create("netty-tcp-proxy", serverSetting);

            if (delegateServer != null && delegateServer.getClass() != this.getClass()) {
                delegateServer.start();
                if (delegateServer.isRunning()) {
                    runMode = delegateServer.getClass().getSimpleName();
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("加载 TCP 代理失败: {}", e.getMessage());
        }
        return false;
    }

    // 以下保留原有的 NIO 实现代码作为参考，但不再使用
    @Deprecated
    @SuppressWarnings("unused")
    private void startNioProxy() throws Exception {
        log.info("启动 NIO TCP 代理服务器 - 地址: {}:{} -> {}:{}",
                serverSetting.getHost(), serverSetting.getPort(), targetHost, targetPort);

        // 配置高并发参数
        configureHighConcurrency();

        // 创建工作线程池
        workerPool = new ThreadPoolExecutor(
                effectiveWorkerThreads, effectiveWorkerThreads * 2,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10000),
                r -> {
                    Thread t = new Thread(r, "nio-tcp-proxy-worker-" + connectionIdCounter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // 打开 Selector 和 ServerSocketChannel
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().setReuseAddress(true);
        int backlog = serverSetting.isAutomaticOptimization()
                ? Math.max(1024, Runtime.getRuntime().availableProcessors() * 256)
                : (serverSetting.getBacklog() > 0 ? serverSetting.getBacklog() : 1024);
        serverChannel.socket().bind(new InetSocketAddress(serverSetting.getHost(), serverSetting.getPort()), backlog);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        running = true;

        // 启动接受连接线程
        acceptThread = new Thread(this::acceptLoop, "nio-tcp-proxy-acceptor");
        acceptThread.setDaemon(true);
        acceptThread.start();

        log.info("✅ NIO TCP 代理服务器启动 [降级模式] - 地址: {}:{} -> {}:{} - Workers: {} - Buffer: {}KB",
                serverSetting.getHost(), serverSetting.getPort(),
                targetHost, targetPort,
                effectiveWorkerThreads, effectiveBufferSize / 1024);
    }

    /**
     * 配置高并发参数
     */
    private void configureHighConcurrency() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long maxMemory = Runtime.getRuntime().maxMemory();

        if (serverSetting.isAutomaticOptimization()) {
            effectiveWorkerThreads = Math.max(4, cpuCores * 2);
            effectiveBufferSize = Math.max(65536, cpuCores * 16384);
            long memoryPerConnection = 64 * 1024L;
            long availableForConnections = (long) (maxMemory * 0.5);
            effectiveMaxConnections = (int) Math.min(50000, availableForConnections / memoryPerConnection);
        } else {
            effectiveWorkerThreads = serverSetting.getWorkerThreads() > 0
                    ? serverSetting.getWorkerThreads() : Math.max(4, cpuCores * 2);
            effectiveBufferSize = serverSetting.getReceiveBufferSize() > 0
                    ? serverSetting.getReceiveBufferSize() : Math.max(65536, cpuCores * 16384);
            effectiveMaxConnections = serverSetting.getMaxConnections() > 0
                    ? serverSetting.getMaxConnections() : 10000;
        }
    }

    /**
     * 接受连接循环
     */
    private void acceptLoop() {
        while (running) {
            try {
                if (selector.select(1000) == 0) {
                    continue;
                }

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectedKeys.iterator();

                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    if (key.isAcceptable()) {
                        handleAccept(key);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    log.error("Selector 错误", e);
                }
            }
        }
    }

    /**
     * 处理新连接
     */
    private void handleAccept(SelectionKey key) {
        try {
            ServerSocketChannel server = (ServerSocketChannel) key.channel();
            SocketChannel client = server.accept();
            if (client != null) {
                client.configureBlocking(true);

                String clientId = "nio-tcp-proxy-" + connectionIdCounter.incrementAndGet();
                activeConnections.incrementAndGet();
                totalConnections.incrementAndGet();

                if (log.isDebugEnabled()) {
                    log.debug("新连接: {} from {}", clientId, client.getRemoteAddress());
                }

                fireOnOpen(clientId, null);

                // 提交到线程池处理
                workerPool.submit(() -> handleClient(client, clientId));
            }
        } catch (IOException e) {
            log.error("接受连接失败", e);
        }
    }

    /**
     * 处理客户端连接
     */
    private void handleClient(SocketChannel client, String clientId) {
        Socket targetSocket = null;
        try {
            // 连接目标服务器
            targetSocket = new Socket();
            targetSocket.connect(new InetSocketAddress(targetHost, targetPort), 10000);

            if (log.isDebugEnabled()) {
                log.debug("已建立到目标服务器的连接: {}:{}", targetHost, targetPort);
            }

            // 双向转发数据
            Socket clientSocket = client.socket();
            Socket finalTarget = targetSocket;

            CompletableFuture<Void> clientToTarget = CompletableFuture.runAsync(() ->
                    transfer(clientSocket, finalTarget, "client->target"), workerPool);
            CompletableFuture<Void> targetToClient = CompletableFuture.runAsync(() ->
                    transfer(finalTarget, clientSocket, "target->client"), workerPool);

            // 等待任一方向关闭
            CompletableFuture.anyOf(clientToTarget, targetToClient).join();

        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("TCP 转发失败: {}:{} - {}", targetHost, targetPort, e.getMessage());
            }
        } finally {
            closeQuietly(targetSocket);
            closeQuietly(client);
            activeConnections.decrementAndGet();
            fireOnClose(clientId, null);
        }
    }

    /**
     * 数据转发
     */
    private void transfer(Socket from, Socket to, String direction) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buffer = new byte[effectiveBufferSize];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (IOException e) {
            // 连接关闭
            if (log.isDebugEnabled()) {
                log.debug("转发结束 [{}]: {}", direction, e.getMessage());
            }
        }
    }

    private void closeQuietly(SocketChannel channel) {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    protected void doStop() throws Exception {
        running = false;

        if (delegateServer != null) {
            delegateServer.stop();
            delegateServer = null;
        }

        if (acceptThread != null) {
            acceptThread.interrupt();
        }

        if (selector != null) {
            selector.close();
        }

        if (serverChannel != null) {
            serverChannel.close();
        }

        if (workerPool != null) {
            workerPool.shutdown();
            workerPool.awaitTermination(5, TimeUnit.SECONDS);
        }

        log.info("NIO TCP 代理服务器停止");
    }

    @Override
    public String getServerInfo() {
        return String.format("TcpProxyServer[%s:%d -> %s:%d, mode=%s, running=%s]",
                serverSetting.getHost(),
                serverSetting.getPort(),
                targetHost,
                targetPort,
                runMode,
                isRunning());
    }

    @Override
    public String getProtocolName() {
        return "TCP-PROXY";
    }

    public String getRunMode() {
        return runMode;
    }
}
