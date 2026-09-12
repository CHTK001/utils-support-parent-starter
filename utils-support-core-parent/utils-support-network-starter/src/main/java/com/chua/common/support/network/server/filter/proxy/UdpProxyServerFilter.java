package com.chua.common.support.network.server.filter.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
* UDP 反向代理过滤器，支持静态路由和动态 {@link ProxyTargetResolver}。
*
* <p>基于 Vert.x {@link DatagramSocket} 实现，监听 UDP 端口，将数据包转发到后端服务并返回响应。</p>
*
* <h2>使用方式</h2>
* <pre>{@code
* // 静态路由（单目标）
* UdpProxyServerFilter proxy = UdpProxyServerFilter.staticRoutes(
*         java.util.Collections.singletonMap("dns", new InetSocketAddress("8.8.8.8", 53)));
*
* // 自定义解析器
* UdpProxyServerFilter proxy = UdpProxyServerFilter.of(5000, remoteAddr -> {
*     // 根据 remoteAddr 返回 Discovery
*     return null;
* });
*
* server.addFilter(proxy);
* proxy.startProxy(15353);
* }</pre> 返回 Discovery
*     return null;
* });
*
* 服务端.添加过滤器(代理);
* 代理.启动代理(15353);
* }</pre>
*
* @author CH
* @since 2026/07/24
 */
@Slf4j
public class UdpProxyServerFilter implements ServerFilter {

    /**
    * 超时时间（毫秒）
     */
    private final int timeoutMs;

    /**
    * 是否运行中
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
    * 后端地址解析器
     */
    private final ProxyTargetResolver targetResolver;

    /**
    * Vert.x 实例
     */
    private Vertx vertx;

    /**
    * 主监听 套接字
     */
    private DatagramSocket serverSocket;

    /** 创建 udp代理服务端过滤器 实例 */
    public UdpProxyServerFilter() {
        this(5000, null);
    }

    /**
    * 创建 udp代理服务端过滤器 实例
    * @param timeoutMs 超时ms
     */
    public UdpProxyServerFilter(int timeoutMs) {
        this(timeoutMs, null);
    }

    /**
    * 创建基于静态路由映射的 UDP 代理过滤器。
    *
    * @param routes 名称 → 后端地址映射（实际仅取第一个非空地址）
    * @return UdpProxyServerFilter 实例
     */
    public static UdpProxyServerFilter staticRoutes(Map<String, InetSocketAddress> routes) {
        Objects.requireNonNull(routes, "routes must not be null");
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("routes must not be empty");
        }
        InetSocketAddress first = routes.values().iterator().next();
        Discovery fixed = Discovery.builder()
                .host(first.getHostName())
                .port(first.getPort())
                .protocol("udp")
                .build();
        return new UdpProxyServerFilter(5000, remote -> fixed);
    }

    /**
    * 创建自定义目标解析器的 UDP 代理过滤器。
    *
    * @param timeoutMs      超时时间（毫秒）
    * @param targetResolver 目标解析器
    * @return UdpProxyServerFilter 实例
     */
    public static UdpProxyServerFilter of(int timeoutMs, ProxyTargetResolver targetResolver) {
        return new UdpProxyServerFilter(timeoutMs, targetResolver);
    }

    /**
    * 创建 udp代理服务端过滤器 实例
    * @param timeoutMs 超时ms
    * @param targetResolver 代理Target解析器
    * @param targetResolver Target解析器
     */
    public UdpProxyServerFilter(int timeoutMs, ProxyTargetResolver targetResolver) {
        this.timeoutMs = timeoutMs;
        this.targetResolver = targetResolver != null ? targetResolver : remoteAddr -> null;
    }

    @Override
    /** 获取订单 */
    public int getOrder() {
        return Integer.MAX_VALUE - 25;
    }

    @Override
    /** 支持协议 */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.UDP};
    }

    @Override
    /** 初始化 */
    public void init(ServerFilterConfig config) {
        this.vertx = Vertx.vertx();
        running.set(true);
        log.info("[network-proxy] UdpProxyServerFilter 初始化完成, timeout={}ms, vertx=true", timeoutMs);
    }

    @Override
    /** 销毁 */
    public void destroy() {
        running.set(false);
        stopProxy();
        if (vertx != null) {
            vertx.close();
        }
        log.info("[network-proxy] UdpProxyServerFilter 已关闭");
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

    /**
    * 启动 UDP 代理服务。
    *
    * @param listenPort 监听端口
     */
    public void startProxy(int listenPort) {
        if (vertx == null) {
            this.vertx = Vertx.vertx();
        }
        running.set(true);
        this.serverSocket = vertx.createDatagramSocket(new DatagramSocketOptions()
                .setReuseAddress(true));
        serverSocket.handler(packet -> handlePacket(packet, serverSocket));
        serverSocket.listen(listenPort, "0.0.0.0")
                .onSuccess(v -> log.info("[network-proxy] UDP 代理启动: port={}, vertx=true", listenPort))
                .onFailure(err -> log.error("[network-proxy] UDP 代理启动失败: port={}", listenPort, err));
    }

    /**
    * 停止代理。
     */
    public void stopProxy() {
        running.set(false);
        if (serverSocket != null) {
            serverSocket.close();
            serverSocket = null;
        }
        log.info("[network-proxy] UDP 代理停止");
    }

    /**
    * 处理数据包
    *
    * @param packet 数据包
    * @param mainSocket main套接字
     */
    private void handlePacket(io.vertx.core.datagram.DatagramPacket packet, DatagramSocket mainSocket) {
        io.vertx.core.net.SocketAddress sender = packet.sender();
        InetSocketAddress senderAddr = new InetSocketAddress(sender.host(), sender.port());
        Discovery discovery = targetResolver.resolve(senderAddr);
        if (discovery == null) {
            log.warn("[network-proxy] 无法解析后端地址 for remote={}", senderAddr);
            return;
        }
        forwardUdp(mainSocket, packet.data(), sender, discovery);
    }

    /**
    * 转发 UDP 数据包到后端。
    *
    * @param mainSocket 主监听 套接字（用于回传响应）
    * @param data       数据
    * @param sender     发送方地址
    * @param discovery  后端地址
     */
    private void forwardUdp(DatagramSocket mainSocket, io.vertx.core.buffer.Buffer data,
                            io.vertx.core.net.SocketAddress sender, Discovery discovery) {
 // 每个请求使用独立临时 套接字 转发并接收后端响应，与后端一问一答
        vertx.createDatagramSocket(new DatagramSocketOptions().setReuseAddress(true))
                .listen(0, "0.0.0.0")
                .onSuccess(tmp -> {
                    tmp.handler(reply -> {
                        mainSocket.send(reply.data(), sender.port(), sender.host());
                        tmp.close();
                    });
                    long timerId = vertx.setTimer(timeoutMs, id -> tmp.close());
                    tmp.send(data, discovery.getPort(), discovery.getHost())
                            .onFailure(err -> {
                                log.debug("[network-proxy] UDP 代理转发异常: {}", err.getMessage());
                                vertx.cancelTimer(timerId);
                                tmp.close();
                            });
                });
    }
}
