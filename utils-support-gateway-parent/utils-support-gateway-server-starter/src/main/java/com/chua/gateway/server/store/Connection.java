package com.chua.gateway.server.store;

/**
 * 连接模型：对一台被控机器的访问入口（被控机器装原生 VNC/SSH/RDP Server）。
 *
 * @param protocol 远控协议类型：vnc | ssh | rdp | rustdesk
 * @param host     远端主机 IP 或域名
 * @param port     远端服务端口（VNC=5900, SSH=22, RDP=3389, RustDesk=21116/21117）
 * @param user     登录用户名（VNC 可能为空）
 * @param password 登录密码（VNC 可能为空）
 * @param key      服务端预配置 key（仅服务端预存才有），用于 key 模式快速查找
 * @author CH
 * @since 4.0.0.42
 */
public record Connection(
        String protocol,
        String host,
        int port,
        String user,
        String password,
        String key
) {
    /**
     * 协议必填
     */
    private static final String MSG_PROTOCOL_REQUIRED = "protocol is required";

    /**
     * 主机必填
     */
    private static final String MSG_HOST_REQUIRED = "host is required";

    /**
     * 端口非法提示模板
     */
    private static final String MSG_INVALID_PORT = "invalid port: ";

    public Connection {
        if (protocol == null || protocol.isBlank()) {
            throw new IllegalArgumentException(MSG_PROTOCOL_REQUIRED);
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(MSG_HOST_REQUIRED);
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException(MSG_INVALID_PORT + port);
        }
    }
}
