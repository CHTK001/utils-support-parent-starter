package com.chua.gateway.server.spi;

import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.tunnel.GatewayTunnel;

/**
 * 远控协议服务器工厂 SPI 接口。
 *
 * <p>每种协议（vnc / ssh / rdp / rustdesk）提供一个实现，
 * 在服务启动时通过 {@code ServiceProvider.of(ProtocolServerFactory.class).collect()}
 * 一性发现并注册。</p>
 *
 * <p>每个实现负责：
 *   1. 解析连接参数（如 SSH 用户名密码、RDP 域名）
 *   2. 创建对应协议的 Tunnel 实例（实现 {@link com.chua.common.support.network.tunnel.Tunnel}）
 *   3. 注册 WebSocket endpoint 路由（如 {@code /ws/vnc/{tunnelId}}）
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ProtocolServerFactory {

    /**
     * 协议类型字符串（小写）。
     *
     * @return "vnc" | "ssh" | "rdp" | "rustdesk"
     */
    String protocol();

    /**
     * 为指定连接创建 Tunnel 实例。
     *
     * @param connection 连接信息（含 host/port/user/password）
     * @param tunnelId   隧道 ID（UUID，前端用此解码 wsUrl）
     * @return 远控 Tunnel 实例
     * @throws Exception 创建失败时抛出
     */
    GatewayTunnel createTunnel(Connection connection, String tunnelId) throws Exception;
}
