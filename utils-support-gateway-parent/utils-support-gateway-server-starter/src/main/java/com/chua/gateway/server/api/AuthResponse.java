package com.chua.gateway.server.api;

/**
 * 鉴权成功响应（3 步流程返回给前端挂载 wsUrl）。
 *
 * @param tunnelId 隧道 ID（UUID）
 * @param wsUrl    浏览器应连接的 WebSocket URL
 * @param protocol 协议类型（vnc/ssh/rdp/rustdesk）
 * @param host     远端主机
 * @param port     远端端口
 * @author CH
 * @since 4.0.0.42
 */
public record AuthResponse(
        String tunnelId,
        String wsUrl,
        String protocol,
        String host,
        int port
) {
}
