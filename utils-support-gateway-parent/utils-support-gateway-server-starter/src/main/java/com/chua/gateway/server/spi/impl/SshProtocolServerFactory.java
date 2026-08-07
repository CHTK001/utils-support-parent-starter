package com.chua.gateway.server.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.gateway.server.bridge.SshBridge;
import com.chua.gateway.server.spi.ProtocolServerFactory;
import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.tunnel.GatewayTunnel;
import lombok.extern.slf4j.Slf4j;

/**
 * SSH 协议服务器工厂。
 *
 * <p>创建 {@link SshBridge} 实例并连接 SSH Server (:22)。
 * 浏览器侧使用 xterm.js 收发伪终端协议。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = "ssh", order = 100)
public final class SshProtocolServerFactory implements ProtocolServerFactory {

    /**
     * SSH 协议标识
     */
    private static final String PROTOCOL_SSH = "ssh";

    @Override
    public String protocol() {
        return PROTOCOL_SSH;
    }

    @Override
    public GatewayTunnel createTunnel(Connection connection, String tunnelId) throws Exception {
        SshBridge bridge = new SshBridge(connection);
        GatewayTunnel tunnel = GatewayTunnel.of(tunnelId, connection, bridge);
        tunnel.open();
        log.info("SSH Tunnel 创建: id={} target={}@{}:{}",
                tunnelId, connection.user(), connection.host(), connection.port());
        return tunnel;
    }
}
