package com.chua.gateway.server.tunnel;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.gateway.server.spi.ProtocolServerFactory;
import com.chua.gateway.server.store.Connection;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tunnel 注册中心（内存 Map）。
 *
 * <p>运行时行为：
 *   <ul>
 *     <li>{@link #open(Connection)} — 查 {@code SPI} 创建并缓存 Tunnel，返回 UUID</li>
 *     <li>{@link #get(String)} — 通过 tunnelId 取出</li>
 *     <li>{@link #close(String)} — 关闭 + 移除</li>
 *   </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class TunnelRegistry {

    /**
     * tunnelId → GatewayTunnel
     */
    private final Map<String, GatewayTunnel> tunnels = new ConcurrentHashMap<>();

    /**
     * SPI 加载器（懒加载，cached）
     */
    private volatile ServiceProvider<ProtocolServerFactory> provider;

    /**
     * 获取 SPI 提供者（首次加载后缓存）。
     *
     * @return ServiceProvider 实例
     */
    private ServiceProvider<ProtocolServerFactory> getProvider() {
        ServiceProvider<ProtocolServerFactory> p = provider;
        if (p == null) {
            synchronized (this) {
                p = provider;
                if (p == null) {
                    p = ServiceProvider.of(ProtocolServerFactory.class);
                    provider = p;
                }
            }
        }
        return p;
    }

    /**
     * 根据协议名查找 SPI factory。
     *
     * @param protocol 协议名
     * @return 命中的 Factory；未找到返回 {@link Optional#empty()}
     */
    private Optional<ProtocolServerFactory> findFactory(String protocol) {
        ProtocolServerFactory match = getProvider().getExtension(protocol);
        return Optional.ofNullable(match);
    }

    /**
     * 创建并打开一个 Tunnel，返回 tunnelId。
     *
     * @param connection 远端连接（含 protocol）
     * @return tunnelId
     * @throws Exception SPI 未注册或 connect 失败
     */
    public String open(Connection connection) throws Exception {
        return open(connection, UUID.randomUUID().toString());
    }

    /**
     * 创建并打开一个 Tunnel，指定 tunnelId（便于测试和调试）。
     *
     * @param connection 远端连接
     * @param tunnelId   外部传入的 ID
     * @return tunnelId
     * @throws Exception SPI 未注册或 connect 失败
     */
    public String open(Connection connection, String tunnelId) throws Exception {
        ProtocolServerFactory factory = findFactory(connection.protocol())
                .orElseThrow(() -> new IllegalStateException("协议未注册: " + connection.protocol()));
        try {
            GatewayTunnel tunnel = factory.createTunnel(connection, tunnelId);
            tunnels.put(tunnelId, tunnel);
            log.info("[gateway-server] Tunnel 已注册: id={} protocol={}", tunnelId, connection.protocol());
        } catch (Exception e) {
            // 目标不可达时仍注册隧道（记录连接信息），由 WS 桥接再建立连接
            GatewayTunnel stub = GatewayTunnel.of(tunnelId, connection, null);
            tunnels.put(tunnelId, stub);
            log.warn("[gateway-server] Tunnel 暂存（目标未连接）: id={} protocol={} err={}",
                    tunnelId, connection.protocol(), e.getMessage());
        }
        return tunnelId;
    }

    /**
     * 取出已存在的 Tunnel。
     *
     * @param tunnelId 隧道 ID
     * @return GatewayTunnel；不存在返回 {@link Optional#empty()}
     */
    public Optional<GatewayTunnel> get(String tunnelId) {
        return Optional.ofNullable(tunnels.get(tunnelId));
    }

    /**
     * 关闭并移除 Tunnel。
     *
     * @param tunnelId 隧道 ID
     */
    public void close(String tunnelId) {
        GatewayTunnel tunnel = tunnels.remove(tunnelId);
        if (tunnel != null) {
            tunnel.close();
            log.info("[gateway-server] Tunnel 已注销: id={}", tunnelId);
        }
    }

    /**
     * 列出全部活跃 Tunnel。
     *
     * @return 集合视图
     */
    public Collection<GatewayTunnel> all() {
        return tunnels.values();
    }

    /**
     * 当前活跃 Tunnel 数量。
     *
     * @return 整数
     */
    public int size() {
        return tunnels.size();
    }
}
