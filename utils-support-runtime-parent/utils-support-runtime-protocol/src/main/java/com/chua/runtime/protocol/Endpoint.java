package com.chua.runtime.protocol;

import com.chua.common.support.utils.StringUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 端点 — 一次传输链路中的节点（来源 / 目标）。
 *
 * <p>关注三个核心属性：</p>
 * <ul>
 *   <li>{@link #kind} — SERVER / CLIENT / PRODUCER / CONSUMER（角色）</li>
 *   <li>{@link #protocol} — 传输协议（HTTP/TCP/ZK/REDIS...）</li>
 *   <li>{@link #software} — 软件栈（JDK_HTTP/TOMCAT/JEDIS/ZK_NATIVE...）</li>
 * </ul>
 *
 * <p>其他字段（host/port/path/attributes）作为补充描述，
 * 用于生成依赖关系图。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Endpoint {

    /**
     * 端点种类（角色）
     */
    private EndpointKind kind;

    /**
     * 传输协议
     */
    private Protocol protocol;

    /**
     * 软件栈
     */
    private Software software;

    /**
      * 主机名或 IP（服务端 端）
     */
    private String host;

    /**
     * 端口
     */
    private int port;

    /**
      * 路径 / URL（HTTP 路径 或 ZK znode 或 Redis 键）
     */
    private String path;

    /**
     * SSL / TLS（用于显示协议是否为加密）
     */
    private boolean ssl;

    /**
      * 实例 标识（同一进程的多个实例区分，如 工人 thread）
     */
    private String instanceId;

    /**
     * 附加属性（动态标签，懒填充）
     */
    @Builder.Default
    private Map<String, String> attributes = new HashMap<>(); // attributes

    /**
      * 端点的稳定唯一 标识（用于依赖图节点去重）。
     *
     * <p>组成：protocol + software + host + port + path。
      * 同一进程同 端点 多次出现视为同一节点。</p>
     *
     * @return 节点 标识
     */
    public String nodeId() {
        StringBuilder sb = new StringBuilder();
        sb.append(protocol != null ? protocol.name() : "?").append('|');
        sb.append(software != null ? software.name() : "?").append('|');
        sb.append(host != null ? host : "?").append(':');
        sb.append(port).append('|');
        sb.append(path != null ? path : "/");
        return sb.toString();
    }

    /**
      * 端点的展示描述，用于依赖图节点 标签。
     *
     * @return 展示描述
     */
    public String displayLabel() {
        StringBuilder sb = new StringBuilder();
        if (protocol != null) {
            sb.append(protocol.displayName());
        }
        if (software != null && software != Software.UNKNOWN) {
            sb.append('/').append(software.displayName());
        }
        sb.append(' ');
        if (host != null) {
            sb.append(host).append(':').append(port);
        }
        if (StringUtils.isNotEmpty(path)) {
            sb.append(path);
        }
        return sb.toString();
    }
}