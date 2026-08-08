package com.chua.gateway.server.server;

import com.chua.gateway.server.bridge.GuacamoleBridge;
import com.chua.gateway.server.bridge.NoVncBridge;
import com.chua.gateway.server.bridge.SshBridge;
import com.chua.gateway.server.tunnel.GatewayTunnel;
import com.chua.gateway.server.tunnel.TunnelRegistry;
import com.jcraft.jsch.ChannelShell;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 端点处理器（noVNC / xterm.js / Guacamole 透传）。
 *
 * <p>支持路径：
 *   <ul>
 *     <li>{@code /ws/vnc/{tunnelId}} — VNC 帧透传</li>
 *     <li>{@code /ws/ssh/{tunnelId}} — SSH 终端（xterm.js 协议）</li>
 *     <li>{@code /ws/rdp/{tunnelId}} — RDP 经 guacd（透传 guacamole 协议）</li>
 *   </ul>
 * </p>
 *
 * <p>实现策略：作为 common-starter HTTP handler 复用同一端口（port 8080）。
 * 通过 {@link #handleText} / {@link #handleBinary} 在 HTTP handler 里
 * 解析 path 并触发 WebSocket upgrade（HTTPServer 自带 ws 支持有限，
 * 本类以 HTTP tunnel 形式实现 — 前端用 fetch + ReadableStream，
 * 或迁移到 Netty 大型实现）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WsEndpointHandler {

    /**
     * 路径前缀
     */
    private static final String WS_PATH_PREFIX = "/ws/";

    /**
     * Tunnel id 不存在提示
     */
    private static final String TUNNEL_NOT_FOUND_MSG = "Tunnel not found";

    /**
     * Tunnel Registry
     */
    private final TunnelRegistry registry;

    /**
     * 活跃 WS 连接映射
     */
    private final Map<String, ActiveSession> sessions = new ConcurrentHashMap<>();

    public WsEndpointHandler(TunnelRegistry registry) {
        this.registry = registry;
    }

    /**
     * active session 描述
     */
    private static final class ActiveSession {
        final GatewayTunnel tunnel;
        final String sessionId;
        volatile boolean closed;

        ActiveSession(GatewayTunnel tunnel, String sessionId) {
            this.tunnel = tunnel;
            this.sessionId = sessionId;
        }
    }

    /**
     * 解析 WebSocket 路径并返回 tunnel。
     *
     * @param path 形如 {@code /ws/vnc/{tunnelId}}
     * @return tunnel；路径非法或 tunnel 不存在返回 {@code null}
     */
    public GatewayTunnel resolveTunnel(String path) {
        if (path == null || !path.startsWith(WS_PATH_PREFIX)) {
            return null;
        }
        String tail = path.substring(WS_PATH_PREFIX.length());
        int slash = tail.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        // tail = "{protocol}/{tunnelId}"
        String tunnelId = tail.substring(slash + 1);
        return registry.get(tunnelId).orElse(null);
    }

    /**
     * 处理客户端请求，确认是否为 WebSocket upgrade。
     *
     * @param path    请求路径
     * @param headers 请求头
     * @return true 表示应当升级为 WebSocket
     */
    public static boolean isWebSocketUpgrade(String path, Map<String, String> headers) {
        if (path == null || !path.startsWith(WS_PATH_PREFIX)) {
            return false;
        }
        if (headers == null) {
            return false;
        }
        String upgrade = headers.get("Upgrade");
        String connection = headers.get("Connection");
        return upgrade != null && upgrade.equalsIgnoreCase("websocket")
                && connection != null && connection.toLowerCase().contains("upgrade");
    }

    /**
     * 客户端断开时清理 session。
     *
     * @param sessionId 内部 session id
     */
    public void onClose(String sessionId) {
        ActiveSession s = sessions.remove(sessionId);
        if (s != null) {
            s.closed = true;
            log.info("[gateway-server] WS 客户端断开: sessionId={}", sessionId);
        }
    }

    /**
     * VNC WS 处理：浏览器侧 noVNC 客户端。
     * 实现：双向透传 WebSocket 二进制帧 ↔ raw TCP RFB 字节。
     * 由 common-starter 的 native WebSocket 支持自动转发到 onBinary/onText 回调。
     */
    public void onVncBinary(ActiveSession session, byte[] payload) {
        if (session.closed) {
            return;
        }
        GatewayTunnel tunnel = session.tunnel;
        if (!(tunnel.bridge() instanceof NoVncBridge bridge)) {
            return;
        }
        try {
            bridge.writeToVncServer(payload);
            pumpVncToClient(bridge, session);
        } catch (IOException e) {
            log.warn("[gateway-server] VNC 写失败: {}", e.getMessage());
        }
    }

    /**
     * 主动从 VNC Server 读 → 推送到客户端。当前实现里通过 {@link com.chua.common.support.network.server.handler.ServerHandler}
     * 在 protocol 完整接入时由 server 内部循环处理。
     *
     * <p>本方法保留为占位，使 SPI 协议（含 VNC/SSH/RDP）通过 Server 接口发布。
     * 实际运行时由 GatewayServerBootstrap 注册的 handler 处理。</p>
     *
     * @param bridge VNC bridge
     * @param session active session
     */
    private void pumpVncToClient(NoVncBridge bridge, ActiveSession session) {
        try {
            byte[] read = bridge.readFromVncServer();
            // 实际写回客户端由 common-starter Server / ServerResponse 完成
            // 由于 common-starter 的 WS API 是内部依赖，此处空实现
            // 完整实现在 Phase C（前端直接 raw socket 或升级到 Netty）
            log.debug("[gateway-server] VNC → 客户端: {} bytes (占位)", read.length);
        } catch (IOException e) {
            log.debug("[gateway-server] VNC 读结束: sessionId={} err={}", session.sessionId, e.getMessage());
        }
    }

    /**
     * SSH WS 处理：浏览器侧 xterm.js 终端。
     * xterm.js 协议：文本模式（input/output UTF-8 文本）。
     * binary 模式一般不使用。
     */
    public void onSshText(ActiveSession session, String text) {
        if (session.closed) {
            return;
        }
        GatewayTunnel tunnel = session.tunnel;
        if (!(tunnel.bridge() instanceof SshBridge bridge)) {
            return;
        }
        ChannelShell ch = bridge.channel();
        if (ch == null) {
            return;
        }
        try {
            OutputStream out = ch.getOutputStream();
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            log.warn("[gateway-server] SSH 写失败: {}", e.getMessage());
        }
    }

    /**
     * 读取 SSH 输出（一次抓多字节，UTF-8 解码）。
     * 本方法为占位实现，实际读循环由后续 Server 内部循环驱动。
     *
     * @param session active session
     * @return 累计读到的文本，超时返回部分结果
     */
    public String readSshOutput(ActiveSession session) {
        if (session.closed) {
            return null;
        }
        GatewayTunnel tunnel = session.tunnel;
        if (!(tunnel.bridge() instanceof SshBridge bridge)) {
            return null;
        }
        ChannelShell ch = bridge.channel();
        if (ch == null) {
            return null;
        }
        try {
            InputStream in = ch.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            if (n <= 0) {
                return null;
            }
            baos.write(buf, 0, n);
            return baos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("[gateway-server] SSH 读结束: {}", e.getMessage());
            return null;
        }
    }

    /**
     * RDP/Guacamole WS 处理：透传二进制。
     */
    public void onRdpBinary(ActiveSession session, byte[] payload) {
        if (session.closed) {
            return;
        }
        GatewayTunnel tunnel = session.tunnel;
        if (!(tunnel.bridge() instanceof GuacamoleBridge bridge)) {
            return;
        }
        if (bridge.socket() == null || bridge.socket().isClosed()) {
            return;
        }
        try {
            OutputStream out = bridge.socket().getOutputStream();
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            log.warn("[gateway-server] Guacamole 写失败: {}", e.getMessage());
        }
    }

    /**
     * 注册/获取 active session。
     */
    public ActiveSession getOrCreate(String sessionId, GatewayTunnel tunnel) {
        return sessions.computeIfAbsent(sessionId, k -> new ActiveSession(tunnel, k));
    }
}
