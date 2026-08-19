package com.chua.gateway.server.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.gateway.server.bridge.RustdeskBridge;
import com.chua.gateway.server.spi.ProtocolServerFactory;
import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.tunnel.GatewayTunnel;
import lombok.extern.slf4j.Slf4j;

/**
 * RustDesk 协议服务器工厂（占位骨架）。
 *
 * <p>创建 {@link RustdeskBridge} 实例。
 * 接入 RustDesk hbbs/hbbr 实现 NAT 穿透的 P2P 远控。</p>
 *
 * <p>本轮仅占位，避免 SPI 注册时丢实现。</p>
 *
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = "rustdesk", order = 100)
public final class RustdeskProtocolServerFactory implements ProtocolServerFactory {

    /**
     * RustDesk 协议标识
     */
    private static final String PROTOCOL_RUSTDESK = "rustdesk";

    @Override
    public String protocol() {
        return PROTOCOL_RUSTDESK;
    }

    @Override
    public GatewayTunnel createTunnel(Connection connection, String tunnelId) throws Exception {
        RustdeskBridge bridge = new RustdeskBridge(connection);
        GatewayTunnel tunnel = GatewayTunnel.of(tunnelId, connection, bridge);
        tunnel.open();
        log.warn("[gateway-server] Rustdesk Tunnel 已占位（尚未实现），id={}", tunnelId);
        return tunnel;
    }
}
