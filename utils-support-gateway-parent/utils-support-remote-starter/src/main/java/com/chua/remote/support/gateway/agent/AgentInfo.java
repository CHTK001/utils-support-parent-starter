package com.chua.remote.support.gateway.agent;

import io.netty.channel.Channel;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Agent 注册信息
 *
 * <p>表示一个远程 Agent 实例的元数据，包括身份标识、协议/传输/编解码能力、
 * 心跳状态、网络地址及 API 网关路由策略等。
 *
 * <p>通过 {@code @Builder} 构建实例，{@code online} 字段动态反映当前在线状态。
 *
 * @author CH
 * @since 4.0.0.41
 */
@Data
@Builder
public class AgentInfo {
    /** Agent 唯一标识 */
    private String agentId;
    /** Agent 通信密钥 */
    /**
     * 密钥
     */
    private String secret;
    /** Agent 类型，如 "java"、"rust" 等 */
    private String agentType;
    /** 支持的协议列表（如 SSH、VNC、RDP 等） */
    private List<String> protocols;
    /** 支持的传输层协议列表 */
    private List<String> transports;
    /** 支持的编解码方式列表（如 H264、JPEG 等） */
    private List<String> codecs;
    /** 能力键值对，描述 Agent 的扩展能力 */
    private Map<String, String> capabilities;
    /** 注册时间 */
    private Instant registeredAt;
    /** 最近一次心跳时间 */
    private Instant lastHeartbeatAt;
    /** 连续心跳缺失次数，超限后将被驱逐 */
    private int heartbeatMissCount;
    /** 是否在线 */
    private boolean online;
    /** Agent 的 IP 地址 */
    private String ipAddress;
    /** 验证码，用于建立连接前的身份校验 */
    /**
     * 验证码
     */
    private String verifyCode;
    /** Agent 暴露的远程端口 */
    private int remotePort;
    /** Agent→网关延迟估算(ms)，来自心跳 sentAt，-1 表示未知 */
    @Builder.Default private long lastRttMs = -1;
    /** Netty 通道引用，transient 不参与序列化 */
    /**
     * 频道
     */
    private transient Channel channel;

    /** API 网关权重（默认 1） */
    @Builder.Default private int weight = 1;
    /** 负载均衡策略: round_robin / least_connections / ip_hash / qps */
    @Builder.Default private String strategy = "round_robin";
    /** 是否被网关管理员停用 */
    @Builder.Default private boolean disabled = false;
    /** API 路径（如 /api/users） */
    @Builder.Default private String apiPath = "";
}
