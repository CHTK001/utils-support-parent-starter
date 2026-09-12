package com.chua.common.support.network.cluster;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 集群服务元数据（壳类）：仅记录路径/地址/协议信息，不承载任何网络 IO。
 *
 * <p>用途：通过 {@link ClusterManager#addServer(ServerEntry)} 声明本节点需要 Scatter 在集群内
 * 发现并路由到的目标服务；scatter 会自动在 seed/gateway 模式下对等扩散此元数据。</p>
 *
 * <p>同一路径下只允许注册同一种协议（http 或 tcp），混用会抛出 {@link IllegalArgumentException}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServerEntry {

    /** 服务路径，如 "/api"、"/薪酬" */
    private String servicePath;

    /** 目标主机 */
    private String host;

    /** 目标端口 */
    private int port;

    /** 协议：http / tcp / udp */
    private String protocol;

    /** 业务分组（空 时回落到 clustersetting.scatterid） */
    private String scatterId;

    /**
     * 便捷构造：HTTP 服务。
     * @param servicePath 服务路径
     * @param host 主机
     * @param port 端口
     * @return http的结果
     */
    public static ServerEntry http(String servicePath, String host, int port) {
        return new ServerEntry(servicePath, host, port, "http", null);
    }

    /**
     * 便捷构造：TCP 服务。
     * @param servicePath 服务路径
     * @param host 主机
     * @param port 端口
     * @return tcp的结果
     */
    public static ServerEntry tcp(String servicePath, String host, int port) {
        return new ServerEntry(servicePath, host, port, "tcp", null);
    }

    /**
     * 校验参数合法性。
     *
     * @throws IllegalArgumentException 参数非法时抛出
     */
    public void validate() {
        if (servicePath == null || servicePath.isBlank()) {
            throw new IllegalArgumentException("servicePath 不能为空");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host 不能为空");
        }
        if (port <= 0) {
            throw new IllegalArgumentException("port 必须 > 0, got: " + port);
        }
        String proto = protocol == null || protocol.isBlank() ? "http" : protocol.toLowerCase();
        if (!"http".equals(proto) && !"tcp".equals(proto) && !"udp".equals(proto)) {
            throw new IllegalArgumentException("protocol 仅支持 http/tcp/udp, got: " + protocol);
        }
    }

    /**
     * 获取规范化协议名。
     * @return normalized协议的结果
     */
    public String normalizedProtocol() {
        return protocol == null || protocol.isBlank() ? "http" : protocol.toLowerCase();
    }
}
