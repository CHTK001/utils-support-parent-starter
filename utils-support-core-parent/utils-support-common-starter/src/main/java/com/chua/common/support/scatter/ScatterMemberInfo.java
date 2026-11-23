package com.chua.common.support.scatter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * scatter 成员快照信息（{@link ScatterStats#members} 的元素）。
 *
 * <p>由 {@link ScatterServiceDiscovery#stats()} 生成的只读视图，
 * 反映某一时刻本节点视角下的集群成员状态。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class ScatterMemberInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 成员唯一标识（serverId）
     */
    private String serverId;
    /**
     * 主机地址
     */
    private String host;
    /**
     * scatter 通信端口
     */
    private int port;
    /**
     * 传输协议：tcp / udp
     */
    private String protocol;
    /**
     * 动态权重（0.1~1.0）
     */
    private double weight;
    /**
     * 是否 seed 引导/网关节点（metadata.seed=true，不参与业务负载均衡）
     */
    private boolean seed;
    /**
     * 是否本节点自身
     */
    private boolean self;
    /**
     * 当前连续心跳失败次数（0 表示最近一次心跳正常）
     */
    private int heartbeatFailures;
    /**
     * 本节点最近一次收到该成员信息的时间戳（毫秒；仅本节点视角）
     */
    private long lastSeenAtMillis;
}
