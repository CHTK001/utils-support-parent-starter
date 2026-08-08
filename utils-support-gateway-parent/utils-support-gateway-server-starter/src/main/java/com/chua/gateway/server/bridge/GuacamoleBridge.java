package com.chua.gateway.server.bridge;

import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.tunnel.GatewayTunnel;
import lombok.extern.slf4j.Slf4j;

import java.net.Socket;

/**
 * RDP / VNC / SSH 协议桥接器（经 guacd 子进程）。
 *
 * <p>浏览器侧使用 {@code @guacamole/client}（HTML5 client）。
 * 连接流程：浏览器 → WS frame ↔ 桥接器 ↔ guacd TCP :4822 ↔ guacd ↔ RDP/VNC/SSH 服务器。</p>
 *
 * <p>本类不直接处理 RDP 协议字节 — 由 guacd 子进程（C 守护）转换。
 * 我们只负责建立到 guacd :4822 的 TCP socket，并透传 WS 帧。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class GuacamoleBridge implements RemoteBridge {

    /**
     * 默认 guacd 端口（与 application.properties 中 gateway.guacd.port 默认值一致）
     */
    public static final int DEFAULT_GUACD_PORT = 4822;

    /**
     * guacd 读取超时（毫秒）
     */
    private static final int GUACD_READ_TIMEOUT_MS = 1000;

    /**
     * 底层连接
     */
    private final Connection connection;

    /**
     * guacd host（默认 127.0.0.1，本地子进程）
     */
    private final String guacdHost;

    /**
     * guacd port
     */
    private final int guacdPort;

    /**
     * 到 guacd 的 TCP socket
     */
    private volatile Socket socket;

    public GuacamoleBridge(Connection connection, String guacdHost, int guacdPort) {
        this.connection = connection;
        this.guacdHost = guacdHost;
        this.guacdPort = guacdPort;
    }

    @Override
    public Connection connection() {
        return connection;
    }

    @Override
    public void connect() throws Exception {
        if (socket != null && !socket.isClosed()) {
            return;
        }
        log.info("[gateway-server] Guacamole 连接: guacd={}:{} target={}:{}",
                guacdHost, guacdPort, connection.host(), connection.port());
        socket = new Socket(guacdHost, guacdPort);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(GUACD_READ_TIMEOUT_MS);
        log.info("[gateway-server] Guacamole socket 建立: target={}:{}", connection.host(), connection.port());
    }

    @Override
    public void disconnect() {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (Exception e) {
                log.warn("[gateway-server] Guacamole socket 关闭失败: {}", e.getMessage());
            }
        }
        socket = null;
    }

    @Override
    public boolean isConnected() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    /**
     * 返回到 guacd 的 TCP socket（供 WS handler 透传）。
     *
     * @return Socket 实例，未连接返回 {@code null}
     */
    public Socket socket() {
        return socket;
    }
}
