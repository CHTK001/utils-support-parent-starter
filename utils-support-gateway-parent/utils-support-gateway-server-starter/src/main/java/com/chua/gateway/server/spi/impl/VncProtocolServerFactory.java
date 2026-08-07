package com.chua.gateway.server.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.gateway.server.bridge.NoVncBridge;
import com.chua.gateway.server.spi.ProtocolServerFactory;
import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.tunnel.GatewayTunnel;
import lombok.extern.slf4j.Slf4j;

/**
 * VNC 协议服务器工厂。
 *
 * <p>创建 {@link NoVncBridge} 实例并返回封装好的 {@link GatewayTunnel}。
 * 浏览器侧连接 noVNC 客户端，协议字节通过 WebSocket 透明转发到 VNC Server。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = "vnc", order = 100)
public final class VncProtocolServerFactory implements ProtocolServerFactory {

    /**
     * VNC 协议标识
     */
    private static final String PROTOCOL_VNC = "vnc";

    /**
     * {@inheritDoc}
     *
     * @return 始终返回 {@code "vnc"}
     */
    @Override
    public String protocol() {
        return PROTOCOL_VNC;
    }

    @Override
    public GatewayTunnel createTunnel(Connection connection, String tunnelId) throws Exception {
        NoVncBridge bridge = new NoVncBridge(connection);
        GatewayTunnel tunnel = GatewayTunnel.of(tunnelId, connection, bridge);
        // 调用 bridge.connect() 通过隧道
        bridge.connect();
        tunnel.open();
        log.info("VNC Tunnel 创建: id={} target={}:{}", tunnelId, connection.host(), connection.port());
        return tunnel;
    }
}
