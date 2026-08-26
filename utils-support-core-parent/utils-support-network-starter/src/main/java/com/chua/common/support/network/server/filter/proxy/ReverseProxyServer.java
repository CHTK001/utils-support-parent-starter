package com.chua.common.support.network.server.filter.proxy;

import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;
import com.chua.common.support.network.server.filter.discovery.ServiceDiscoveryServerFilter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP 反向代理服务器：开箱即用的代理门面。
 *
 * <p>内部直接组合 {@link ServiceDiscoveryServerFilter}（服务发现/路由/负载均衡）
 * 与 {@link ReverseProxyServerFilter}（HTTP/WebSocket 转发），链式配置即可搭建
 * 基于服务发现的反向代理：</p>
 *
 * <pre>{@code
 * ReverseProxyServer proxy = new ReverseProxyServer(8080)
 *         .discovery(serviceDiscovery)
 *         .route("/api/**", "/api")
 *         .scatterId("order")
 *         .protocol("http")
 *         .balance("weight")
 *         .start();
 * int port = proxy.getPort();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see ServiceDiscoveryServerFilter
 * @see ReverseProxyServerFilter
 */
public class ReverseProxyServer implements AutoCloseable {

    /** 默认转发超时（秒）。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 默认监听地址。 */
    private static final String DEFAULT_HOST = "127.0.0.1";

    /** 底层 HTTP 服务器。 */
    private final Server server;

    /** 转发超时（秒）。 */
    private final int timeoutSeconds;

    /** 服务发现实例（与发现名二选一）。 */
    private ServiceDiscovery serviceDiscovery;

    /** 服务发现名称（SPI 查找用，与实例二选一）。 */
    private String discoveryName;

    /** 路由映射：匹配模式 -> 服务路径。 */
    private final Map<String, String> routes = new LinkedHashMap<>();

    /** 集群标识。 */
    private String scatterId;

    /** 协议过滤。 */
    private String protocol;

    /** 负载均衡策略。 */
    private String balance;

    /** 排除的服务 ID。 */
    private String excludeServerId;

    /**
     * 构造反向代理（本机随机端口，超时 30 秒）。
     */
    public ReverseProxyServer() {
        this(DEFAULT_HOST, 0, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 构造反向代理。
     *
     * @param port 监听端口（0 为随机）
     */
    public ReverseProxyServer(int port) {
        this(DEFAULT_HOST, port, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 构造反向代理。
     *
     * @param host           监听地址
     * @param port           监听端口（0 为随机）
     * @param timeoutSeconds 转发超时（秒）
     */
    public ReverseProxyServer(String host, int port, int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        this.server = ServerBuilder.create().type("jdk-http")
                .host(host != null && !host.isBlank() ? host : DEFAULT_HOST)
                .port(port)
                .build();
    }

    /**
     * 绑定服务发现实例。
     *
     * @param serviceDiscovery 服务发现
     * @return this
     */
    public ReverseProxyServer discovery(ServiceDiscovery serviceDiscovery) {
        this.serviceDiscovery = Objects.requireNonNull(serviceDiscovery, "serviceDiscovery");
        return this;
    }

    /**
     * 以 SPI 发现名绑定服务发现。
     *
     * @param discoveryName 服务发现名称
     * @return this
     */
    public ReverseProxyServer discoveryName(String discoveryName) {
        this.discoveryName = Objects.requireNonNull(discoveryName, "discoveryName");
        return this;
    }

    /**
     * 添加路由前缀映射。
     *
     * @param pathPrefix  请求路径匹配模式（如 {@code /api/**}）
     * @param servicePath 注册的服务路径
     * @return this
     */
    public ReverseProxyServer route(String pathPrefix, String servicePath) {
        routes.put(pathPrefix, servicePath);
        return this;
    }

    /**
     * 设置集群标识。
     *
     * @param scatterId 集群标识
     * @return this
     */
    public ReverseProxyServer scatterId(String scatterId) {
        this.scatterId = scatterId;
        return this;
    }

    /**
     * 设置协议过滤。
     *
     * @param protocol 协议（如 http）
     * @return this
     */
    public ReverseProxyServer protocol(String protocol) {
        this.protocol = protocol;
        return this;
    }

    /**
     * 设置负载均衡策略。
     *
     * @param balance 策略名（如 weight）
     * @return this
     */
    public ReverseProxyServer balance(String balance) {
        this.balance = balance;
        return this;
    }

    /**
     * 设置排除的服务 ID（避免自转发）。
     *
     * @param excludeServerId 服务 ID
     * @return this
     */
    public ReverseProxyServer excludeServerId(String excludeServerId) {
        this.excludeServerId = excludeServerId;
        return this;
    }

    /**
     * 组装过滤器并启动代理服务器。
     *
     * @return this
     * @throws Exception 启动异常
     */
    public synchronized ReverseProxyServer start() throws Exception {
        ServiceDiscoveryServerFilter filter = serviceDiscovery != null
                ? new ServiceDiscoveryServerFilter(serviceDiscovery)
                : new ServiceDiscoveryServerFilter(discoveryName);
        routes.forEach(filter::addRoute);
        if (scatterId != null) {
            filter.setScatterId(scatterId);
        }
        if (protocol != null) {
            filter.setProtocol(protocol);
        }
        if (balance != null) {
            filter.setBalance(balance);
        }
        if (excludeServerId != null) {
            filter.setExcludeServerId(excludeServerId);
        }
        server.addFilter(filter);
        server.addFilter(new ReverseProxyServerFilter(timeoutSeconds));
        server.start();
        return this;
    }

    /**
     * 获取实际监听端口。
     *
     * @return 端口
     */
    public int getPort() {
        return server.getPort();
    }

    @Override
    public void close() throws Exception {
        server.close();
    }
}
