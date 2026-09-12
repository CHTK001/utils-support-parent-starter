package com.chua.common.support.ai.agent;

import lombok.Builder;

import java.io.Serializable;

/**
* Agent 执行事件。
*
* <p>记录 Agent 执行过程中的事件信息，用于追踪和监控。
*
* @author CH
* @since 2026/07/15
 */
@Builder
public record AgentEvent(
        /** 事件类型 */
        String type,
        /** 事件描述 */
        String message,
        /** 事件关联的 Agent 标识 */
        String agentId,
        /** 事件时间戳 */
        long timestamp
) implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;
}
