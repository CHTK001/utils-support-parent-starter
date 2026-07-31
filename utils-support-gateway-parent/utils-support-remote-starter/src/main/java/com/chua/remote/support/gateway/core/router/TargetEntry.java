package com.chua.remote.support.gateway.core.router;

import com.chua.remote.support.gateway.config.Protocol;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 目标条目。
 * <p>表示一个可被控制端连接的远程目标节点。每个目标与一个 Agent 关联，
 * 包含协议类型、传输方式和地址端口等信息。
 * 控制端通过目标 ID 发起连接，网关负责路由到对应的 Agent。</p>
 *
 * @author CH
 */
@Data
@Builder
public class TargetEntry {
    /** 目标唯一标识（12 位十六进制字符串，由 Agent ID + 协议 + 传输方式 MD5 生成） */
    private String targetId;
    /** 所属 Agent ID */
    private String agentId;
    /** 目标主机地址 */
    /**
     * 主机名
     */
    private String host;
    /** 目标端口号 */
    /**
     * 端口号
     */
    private int port;
    /** 协议类型（SSH / DESKTOP / HTTP 等） */
    private Protocol protocol;
    /** 注册时间 */
    private Instant registeredAt;
    /** 最后心跳时间 */
    private Instant lastHeartbeatAt;
    /** 扩展元数据 */
    @Builder.Default
    private Map<String, String> metadata = new ConcurrentHashMap<>();
    /**
     * 传输协议。
     传输协议。</p>
     */
    @Builder.Default private String transport = "TCP";
}
