package com.chua.gateway.server.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.gateway.server.bridge.GuacamoleBridge;
import com.chua.gateway.server.config.GatewayProperties;
import com.chua.gateway.server.spi.ProtocolServerFactory;
import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.tunnel.GatewayTunnel;
import lombok.extern.slf4j.Slf4j;

/**
 * RDP / VNC / SSH 多协议服务器工厂（经 guacd 子进程）。
 *
 * <p>创建 {@link GuacamoleBridge} 实例并连接到本地 guacd 子进程（默认 :4822）。
 * 浏览器侧使用 {@code @guacamole/client}（HTML5 client）。
 * 桥接器只透传 guacamole 协议字节给 guacd，由 guacd 完成 RDP/VNC/SSH 转换。</p>
 *
 * <p>本实现使用 {@code rdp} 作为 SPI 名称，但实际可经 guacd 支持 RDP/VNC/SSH。
 * 如专用于 RDP，建议调用方传 {@link Connection#protocol()} = "rdp"。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = "rdp", order = 100)
public final class RdpProtocolServerFactory implements ProtocolServerFactory {

    /**
     * RDP 协议标识
     */
    private static final String PROTOCOL_RDP = "rdp";

    @Override
    public String protocol() {
        return PROTOCOL_RDP;
    }

    @Override
    public GatewayTunnel createTunnel(Connection connection, String tunnelId) throws Exception {
        String host = GatewayProperties.guacdHost();
        int port = GatewayProperties.guacdPort();
        GuacamoleBridge bridge = new GuacamoleBridge(connection, host, port);
        GatewayTunnel tunnel = GatewayTunnel.of(tunnelId, connection, bridge);
        tunnel.open();
        log.info("[gateway-server] RDP Tunnel 创建: id={} guacd={}:{} target={}:{}",
                tunnelId, host, port, connection.host(), connection.port());
        return tunnel;
    }
}
