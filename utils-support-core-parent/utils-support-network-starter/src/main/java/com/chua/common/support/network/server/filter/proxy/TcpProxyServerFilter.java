package com.chua.common.support.network.server.filter.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP 反向代理过滤器，支持动态后端选择。
 *
 * <p>基于 Vert.x {@link NetServer} / {@link NetClient} 实现，后端地址由
 * {@link ProxyTargetResolver} 决定，可以基于静态路由或服务发现。</p>
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
    private final ProxyTargetResolver targetResolver;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private Vertx vertx;
    private NetServer netServer;
    private NetClient netClient;

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
    public void init(ServerFilterConfig config) {
        this.vertx = Vertx.vertx();
        this.netClient = vertx.createNetClient(new NetClientOptions()
                .setConnectTimeout(connectTimeoutMs)
                .setTcpNoDelay(true));
        running.set(true);
        log.info("[network-proxy] TcpProxyServerFilter 初始化完成, connectTimeout={}ms, readTimeout={}ms, vertx=true",
                connectTimeoutMs, readTimeoutMs);
    }

    @Override
    public void destroy() {
        running.set(false);
        stopProxy();
        if (netClient != null) {
            netClient.close();
        }
        if (vertx != null) {
            vertx.close();
        }
        log.info("[network-proxy] TcpProxyServerFilter 已关闭");
    }

    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        chain.doFilter(request, response);
    }

    public void stopProxy() {
        running.set(false);
        if (netServer != null) {
            netServer.close();
        }
    }

    public int getActiveConnections() {
        return activeConnections.get();
    }

    public void startProxy(int listenPort) {
        startProxy(listenPort, 0);
    }

    public void startProxy(int listenPort, int backlog) {
        if (vertx == null) {
            this.vertx = Vertx.vertx();
        }
        if (netClient == null) {
            this.netClient = vertx.createNetClient(new NetClientOptions()
                    .setConnectTimeout(connectTimeoutMs)
                    .setTcpNoDelay(true));
        }
        int actualBacklog = backlog > 0 ? backlog : 128;
        this.netServer = vertx.createNetServer(new NetServerOptions()
                .setAcceptBacklog(actualBacklog)
                .setTcpNoDelay(true));
        netServer.connectHandler(clientSocket -> handleConnection(clientSocket));
        running.set(true);
        netServer.listen(listenPort)
                .onSuccess(v -> log.info("[network-proxy] TCP 代理启动: port={}, backlog={}, vertx=true",
                        listenPort, actualBacklog))
                .onFailure(err -> log.error("[network-proxy] TCP 代理启动失败: port={}", listenPort, err));
    }

    private void handleConnection(NetSocket clientSocket) {
        InetSocketAddress remote = remoteAddress(clientSocket);
        Discovery discovery = targetResolver.resolve(remote);
        if (discovery == null) {
            log.warn("[network-proxy] 无法解析后端地址 for remote={}", remote);
            clientSocket.close();
            return;
        }
        activeConnections.incrementAndGet();
        netClient.connect(discovery.getPort(), discovery.getHost())
                .onSuccess(backendSocket -> {
                    log.debug("[network-proxy] TCP 代理连接建立: {} -> {}:{}",
                            remote, discovery.getHost(), discovery.getPort());
                    clientSocket.pipeTo(backendSocket)
                            .onComplete(r -> backendSocket.close());
                    backendSocket.pipeTo(clientSocket)
                            .onComplete(r -> clientSocket.close());
                })
                .onFailure(err -> {
                    log.debug("[network-proxy] TCP 代理连接失败: {} -> {}:{}: {}",
                            remote, discovery.getHost(), discovery.getPort(), err.getMessage());
                    clientSocket.close();
                    activeConnections.decrementAndGet();
                });
    }

    private static InetSocketAddress remoteAddress(NetSocket socket) {
        io.vertx.core.net.SocketAddress addr = socket.remoteAddress();
        return new InetSocketAddress(addr.host(), addr.port());
    }
}
