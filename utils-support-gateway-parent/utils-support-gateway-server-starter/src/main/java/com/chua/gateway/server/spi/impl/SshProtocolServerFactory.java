package com.chua.gateway.server.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.gateway.server.bridge.GuacamoleBridge;
import com.chua.gateway.server.config.GatewayProperties;
import com.chua.gateway.server.spi.ProtocolServerFactory;
import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.tunnel.GatewayTunnel;
import lombok.extern.slf4j.Slf4j;

/**
 * SSH 协议服务器工厂（统一经 guacd 处理，{@code guacamole-common-js} 前端）。
 *
 * <p>创建 {@link GuacamoleBridge} 实例连接到本地 guacd 子进程（默认 :4822），
 * 并在 {@link GatewayTunnel#open()} 时由 {@code WsEndpointHandler} 调用
 * {@link GuacamoleBridge#writeSelectInstruction()} 发送 {@code select ssh ...} 指令。
 * 浏览器侧使用 {@code @guacamole/client}（HTML5 client），与 RDP/VNC 共用同一个 viewer。</p>
 *
 * <p>对比旧实现（旧 {@code SshBridge} 用 JSCH 直连 SSH:22，前端用 xterm.js）：
 * 新实现统一协议栈，避免前端维护两套 viewer（{@code ReRdpViewer} 与 {@code ReSshViewer}），
 * 也不需要在网关内嵌入 JSCH。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = "ssh", order = 100)
public final class SshProtocolServerFactory implements ProtocolServerFactory {

    /**
     * SSH 协议标识（与 guacd 协议名一致）
     */
    private static final String PROTOCOL_SSH = "ssh";

    @Override
    public String protocol() {
        return PROTOCOL_SSH;
    }

    @Override
    public GatewayTunnel createTunnel(Connection connection, String tunnelId) throws Exception {
        String host = GatewayProperties.guacdHost();
        int port = GatewayProperties.guacdPort();
        // 统一协议栈：connection.protocol() 已经是 "ssh"，GuacamoleBridge.writeSelectInstruction 会发 select ssh ...
        GuacamoleBridge bridge = new GuacamoleBridge(connection, host, port);
        GatewayTunnel tunnel = GatewayTunnel.of(tunnelId, connection, bridge);
        tunnel.open();
        log.info("[gateway-server] SSH Tunnel 创建: id={} guacd={}:{} target={}@{}:{}",
                tunnelId, host, port, connection.user(), connection.host(), connection.port());
        return tunnel;
    }
}
