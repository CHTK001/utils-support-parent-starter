package com.chua.common.support.scatter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * scatter 运行观测快照（自节点视角）。
 *
 * <p>由 {@link ScatterServiceDiscovery#stats()} 生成，聚合成员表、
 * 发现/同步/心跳/帧处理计数器，供管理端点或控制台页面展示。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class ScatterStats implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 本节点 ID
     */
    private String nodeId;
    /**
     * 分组标识
     */
    private String groupId;
    /**
     * 传输协议：tcp / udp
     */
    private String protocol;
    /**
     * 发现模式：seed / auto-tcp / auto-udp / standalone
     */
    private String mode;
    /**
     * 当前成员数（含自身与 seed 条目）
     */
    private int memberCount;
    /**
     * 成员快照
     */
    private List<ScatterMemberInfo> members;

    /**
     * 累计发现轮数
     */
    private long discoveryRounds;
    /**
     * 发现轮异常次数
     */
    private long roundFailures;
    /**
     * 同步（拉取合并）成功次数
     */
    private long syncSuccesses;
    /**
     * 同步（拉取合并）失败次数
     */
    private long syncFailures;
    /**
     * 心跳探活发送次数
     */
    private long heartbeatsSent;
    /**
     * 心跳探活失败次数（未达剔除阈值）
     */
    private long heartbeatFailures;
    /**
     * 成员新增次数（含自身注册与远端合并入表）
     */
    private long nodesAdded;
    /**
     * 成员地址/协议变化更新次数（仅权重变化不计入）
     */
    private long nodesUpdated;
    /**
     * 成员剔除次数（心跳超限 / 优雅离网 / 注销）
     */
    private long nodesRemoved;

    /**
     * 收到的 REQ 帧数
     */
    private long reqFramesReceived;
    /**
     * 收到的 PUSH 帧数
     */
    private long pushFramesReceived;
    /**
     * 收到的 ELEC 选举帧数
     */
    private long elecFramesReceived;
    /**
     * 收到的 DISC 自动发现宣告帧数
     */
    private long discFramesReceived;
    /**
     * 收到的 LEAVE 优雅离网帧数
     */
    private long leaveFramesReceived;
    /**
     * 收到的未知类型帧数
     */
    private long unknownFrames;
    /**
     * 最后一轮发现完成时间戳（毫秒；0 表示尚未完成任何一轮）
     */
    private long lastRoundAtMillis;
}
