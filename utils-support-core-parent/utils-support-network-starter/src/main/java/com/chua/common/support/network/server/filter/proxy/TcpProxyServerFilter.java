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
* }</pre> = new TcpProxyServerFilter(5000, 30000, remoteAddr -> {
*     return null;
* });
*
* 服务端.添加过滤器(代理);
* 代理.启动代理(7000); // 监听本地端口
* }</pre>
*
* @author CH
* @since 2026/07/24
 */
@Slf4j
public class TcpProxyServerFilter implements ServerFilter {

    /** 连接超时MS */
    private final int connectTimeoutMs;
    /** 读取超时MS */
    private final int readTimeoutMs;
    /** 目标解析器 */
    private final ProxyTargetResolver targetResolver;
    /** Running */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 是否激活connections */
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    /** Vertx */
    private Vertx vertx;
    /** NET服务器 */
    private NetServer netServer;
    /** NET客户端 */
    private NetClient netClient;

    /** 创建 tcp代理服务端过滤器 实例 */
    public TcpProxyServerFilter() {
        this(5000, 30000, null);
    }

    /**
    * 创建 tcp代理服务端过滤器 实例
    * @param connectTimeoutMs 连接超时ms
    * @param connectTimeoutMs int
    * @param readTimeoutMs 读取超时ms
     */
    public TcpProxyServerFilter(int connectTimeoutMs, int readTimeoutMs) {
        this(connectTimeoutMs, readTimeoutMs, null);
    }

    /**
    * 静态routes
    *
    * @param routes routes
    * @return 静态routes的结果
     */
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

    /**
    * 的
    *
    * @param connectTimeoutMs 连接超时ms
    * @param readTimeoutMs 读取超时ms
    * @param targetResolver Target解析器
    * @return 的的结果
     */
    public static TcpProxyServerFilter of(int connectTimeoutMs, int readTimeoutMs, ProxyTargetResolver targetResolver) {
        return new TcpProxyServerFilter(connectTimeoutMs, readTimeoutMs, targetResolver);
    }

    /**
    * 创建 tcp代理服务端过滤器 实例
    * @param connectTimeoutMs 连接超时ms
    * @param connectTimeoutMs int
    * @param targetResolver 代理Target解析器
    * @param readTimeoutMs 读取超时ms
    * @param targetResolver Target解析器
     */
    public TcpProxyServerFilter(int connectTimeoutMs, int readTimeoutMs, ProxyTargetResolver targetResolver) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.targetResolver = targetResolver != null ? targetResolver : remoteAddr -> null;
    }

    @Override
    /** 获取订单 */
    public int getOrder() {
        return Integer.MAX_VALUE - 30;
    }

    @Override
    /** 支持协议 */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.TCP};
    }

    @Override
    /** 初始化 */
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
    /** 销毁 */
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
    /**
    * 执行过滤
    * @param request 请求
    * @param response 响应
    * @param chain chain
     */
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        chain.doFilter(request, response);
    }

    /** 停止代理 */
    public void stopProxy() {
        running.set(false);
        if (netServer != null) {
            netServer.close();
        }
    }

    /**
    * 获取活跃connections
    *
    * @return 获取活跃connections的结果
     */
    public int getActiveConnections() {
        return activeConnections.get();
    }

    /**
    * 开始代理
    *
    * @param listenPort 监听端口
     */
    public void startProxy(int listenPort) {
        startProxy(listenPort, 0);
    }

    /**
    * 开始代理
    *
    * @param listenPort 监听端口
    * @param backlog backlog
     */
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

    /**
    * 处理Connection
    *
    * @param clientSocket 客户端套接字
     */
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

    /**
    * 远程地址
    *
    * @param socket 套接字
    * @return 远程地址的结果
     */
    private static InetSocketAddress remoteAddress(NetSocket socket) {
        io.vertx.core.net.SocketAddress addr = socket.remoteAddress();
        return new InetSocketAddress(addr.host(), addr.port());
    }
}
