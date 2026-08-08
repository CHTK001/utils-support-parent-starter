package com.chua.common.support.network.server.filter.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP 反向代理过滤器，支持动态后端选择。
 *
 * <p>后端地址由 {@link ProxyTargetResolver} 决定，可以基于静态路由或服务发现。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 1. 静态路由（单目标）
 * TcpProxyServerFilter proxy = TcpProxyServerFilter.staticRoutes(
 *         java.util.Collections.singletonMap("redis", new InetSocketAddress("127.0.0.1", 6379)));
 *
 * // 2. 静态路由（多目标，按顺序取第一个）
 * TcpProxyServerFilter proxy = TcpProxyServerFilter.staticRoutes(Map.of(
 *         "redis1", new InetSocketAddress("127.0.0.1", 6379),
 *         "redis2", new InetSocketAddress("127.0.0.1", 6380)));
 *
 * // 3. 自定义解析器（例如基于服务发现）
 * TcpProxyServerFilter proxy = new TcpProxyServerFilter(5000, 30000, remoteAddr -> {
 *     return null;
 * });
 *
 * server.addFilter(proxy);
 * proxy.startProxy(7000); // 监听本地端口
 * }</pre>
 *
 * @author CH
 * @since 2026/07/24
 */
@Slf4j
public class TcpProxyServerFilter implements ServerFilter {

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final ExecutorService proxyPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ProxyTargetResolver targetResolver;
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private java.net.ServerSocket serverSocket;

    public TcpProxyServerFilter() {
        this(5000, 30000, null);
    }

    public TcpProxyServerFilter(int connectTimeoutMs, int readTimeoutMs) {
        this(connectTimeoutMs, readTimeoutMs, null);
    }

    public static TcpProxyServerFilter staticRoutes(Map<String, InetSocketAddress> routes) {
        Objects.requireNonNull(routes, "routes must not be null");
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("routes must not be empty");
        }
        InetSocketAddress first = routes.values().iterator().next();
        Discovery fixed = Discovery.builder()
                .host(first.getHostName())
                .port(first.getPort())
                .protocol("tcp")
                .build();
        ProxyTargetResolver resolver = remoteAddr -> fixed;
        return new TcpProxyServerFilter(5000, 30000, resolver);
    }

    public static TcpProxyServerFilter of(int connectTimeoutMs, int readTimeoutMs, ProxyTargetResolver targetResolver) {
        return new TcpProxyServerFilter(connectTimeoutMs, readTimeoutMs, targetResolver);
    }

    public TcpProxyServerFilter(int connectTimeoutMs, int readTimeoutMs, ProxyTargetResolver targetResolver) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.targetResolver = targetResolver != null ? targetResolver : remoteAddr -> null;
        int poolSize = Math.max(16, Runtime.getRuntime().availableProcessors() * 4);
        this.proxyPool = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE - 30;
    }

    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.TCP};
    }

    @Override
    public void init(ServerFilterConfig config) throws Exception {
        running.set(true);
        log.info("[network-proxy] TcpProxyServerFilter 初始化完成, connectTimeout={}ms, readTimeout={}ms, virtualThreads=true",
                connectTimeoutMs, readTimeoutMs);
    }

    @Override
    public void destroy() {
        running.set(false);
        stopProxy();
        proxyPool.shutdownNow();
        log.info("[network-proxy] TcpProxyServerFilter 已关闭");
    }

    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        chain.doFilter(request, response);
    }

    public void stopProxy() {
        running.set(false);
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
        }
    }

    public int getActiveConnections() {
        return activeConnections.get();
    }

    public void startProxy(int listenPort) {
        startProxy(listenPort, 0);
    }

    public void startProxy(int listenPort, int backlog) {
        proxyPool.submit(() -> {
            running.set(true);
            try {
                int actualBacklog = backlog > 0 ? backlog : 128;
                serverSocket = new java.net.ServerSocket(listenPort, actualBacklog);
                log.info("[network-proxy] TCP 代理启动: port={}, backlog={}, virtualThreads=true", listenPort, actualBacklog);
                while (running.get()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        InetSocketAddress remote = (InetSocketAddress) clientSocket.getRemoteSocketAddress();
                        Discovery discovery = targetResolver.resolve(remote);
                        if (discovery == null) {
                            log.warn("[network-proxy] 无法解析后端地址 for remote={}", remote);
                            clientSocket.close();
                            continue;
                        }
                        proxyPool.submit(() -> handleConnection(clientSocket, discovery));
                    } catch (Exception e) {
                        if (running.get()) {
                            log.error("[network-proxy] 接受连接异常", e);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[network-proxy] TCP 代理启动失败: port={}", listenPort, e);
            } finally {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try {
                        serverSocket.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void handleConnection(Socket clientSocket, Discovery discovery) {
        activeConnections.incrementAndGet();
        InetSocketAddress backendAddr = new InetSocketAddress(discovery.getHost(), discovery.getPort());
        try (Socket backendSocket = new Socket()) {
            backendSocket.connect(backendAddr, connectTimeoutMs);
            backendSocket.setSoTimeout(readTimeoutMs);
            clientSocket.setSoTimeout(readTimeoutMs);

            log.debug("[network-proxy] TCP 代理连接建立: {} -> {}:{}",
                    clientSocket.getRemoteSocketAddress(),
                    backendAddr.getHostString(), backendAddr.getPort());

            Thread clientToBackend = Thread.ofVirtual()
                    .name("tcp-proxy-c2b-" + clientSocket.getPort())
                    .start(() -> {
                        try {
                            forward(clientSocket.getInputStream(), backendSocket.getOutputStream());
                        } catch (IOException e) {
                            log.debug("[network-proxy] TCP 代理 c2b 流获取失败: {}", e.getMessage());
                        }
                    });
            Thread backendToClient = Thread.ofVirtual()
                    .name("tcp-proxy-b2c-" + clientSocket.getPort())
                    .start(() -> {
                        try {
                            forward(backendSocket.getInputStream(), clientSocket.getOutputStream());
                        } catch (IOException e) {
                            log.debug("[network-proxy] TCP 代理 b2c 流获取失败: {}", e.getMessage());
                        }
                    });

            clientToBackend.join();
            backendToClient.interrupt();

        } catch (Exception e) {
            log.debug("[network-proxy] TCP 代理连接异常: {}", e.getMessage());
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) {}
            activeConnections.decrementAndGet();
        }
    }

    private void forward(InputStream in, OutputStream out) {
        try {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (Exception e) {
            if (running.get()) {
                log.debug("[network-proxy] TCP 转发结束: {}", e.getMessage());
            }
        }
    }
}