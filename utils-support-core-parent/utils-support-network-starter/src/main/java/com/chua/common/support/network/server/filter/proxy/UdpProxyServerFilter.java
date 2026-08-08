package com.chua.common.support.network.server.filter.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Objects;

/**
 * UDP 反向代理过滤器，支持静态路由和动态 {@link ProxyTargetResolver}。
 *
 * <p>监听 UDP 端口，将数据包转发到后端服务并返回响应。</p>
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
     * 代理工作线程池
     */
    private final ExecutorService proxyPool;

    /**
     * 是否运行中
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 后端地址解析器
     */
    private final ProxyTargetResolver targetResolver;

    public UdpProxyServerFilter() {
        this(5000, null);
    }

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

    public UdpProxyServerFilter(int timeoutMs, ProxyTargetResolver targetResolver) {
        this.timeoutMs = timeoutMs;
        this.targetResolver = targetResolver != null ? targetResolver : remoteAddr -> null;
        this.proxyPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "udp-proxy");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE - 25;
    }

    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.UDP};
    }

    @Override
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
        proxyPool.submit(() -> {
            running.set(true);
            try (DatagramSocket socket = new DatagramSocket(listenPort)) {
                socket.setSoTimeout(timeoutMs);
                running.set(true);
                log.info("[network-proxy] UDP 代理启动: port={}", listenPort);

                byte[] buffer = new byte[65535];
                while (running.get()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);

                        byte[] data = new byte[packet.getLength()];
                        System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());

                        InetSocketAddress sender = new InetSocketAddress(packet.getAddress(), packet.getPort());
                        Discovery discovery = targetResolver.resolve(sender);

                        if (discovery != null) {
                            InetSocketAddress backend = new InetSocketAddress(discovery.getHost(), discovery.getPort());
                            proxyPool.submit(() -> forwardUdp(socket, data, sender, backend));
                        } else {
                            log.warn("[network-proxy] 无法解析后端地址");
                        }
                    } catch (Exception e) {
                        if (running.get()) {
                            log.debug("[network-proxy] UDP 代理接收异常: {}", e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[network-proxy] UDP 代理启动失败: port={}", listenPort, e);
            }
        });
    }

    /**
     * 转发 UDP 数据包到后端。
     *
     * @param socket  本地 Socket
     * @param data    数据
     * @param sender  发送方
     * @param backend 后端地址
     */
    private void forwardUdp(DatagramSocket socket, byte[] data, InetSocketAddress sender, InetSocketAddress backend) {
        try (DatagramSocket forwardSocket = new DatagramSocket()) {
            forwardSocket.setSoTimeout(timeoutMs);

            // 发送到后端
            DatagramPacket sendPacket = new DatagramPacket(data, data.length, backend);
            forwardSocket.send(sendPacket);

            // 接收后端响应
            byte[] responseBuffer = new byte[65535];
            DatagramPacket receivePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            forwardSocket.receive(receivePacket);

            byte[] responseData = new byte[receivePacket.getLength()];
            System.arraycopy(receivePacket.getData(), receivePacket.getOffset(), responseData, 0, receivePacket.getLength());

            // 回传给发送方
            DatagramPacket replyPacket = new DatagramPacket(responseData, responseData.length, sender);
            socket.send(replyPacket);

            log.debug("[network-proxy] UDP 代理: {} bytes {} -> {} -> {}", data.length, sender, backend, responseData.length);
        } catch (Exception e) {
            log.debug("[network-proxy] UDP 代理转发异常: {}", e.getMessage());
        }
    }

    /**
     * 停止代理。
     */
    public void stopProxy() {
        running.set(false);
        proxyPool.shutdownNow();
        log.info("[network-proxy] UDP 代理停止");
    }
}