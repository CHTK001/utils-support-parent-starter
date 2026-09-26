package com.chua.common.support.ai.agent;

import lombok.Builder;

import java.io.Serializable;
import java.util.Objects;

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
        /**
         * 事件类型
         */
        String type,
        /**
         * 事件描述
         */
        String message,
        /**
         * 事件关联的 Agent 标识
         */
        String agentId,
        /**
         * 事件时间戳
         */
        long timestamp
) implements Serializable {

    /**
     * 规范构造器：对语义必填组件做空值校验。
     *
     * <p>value class 前置条件——规范构造器必须空值敌对。
     * {@code timestamp} 为基本类型，无需校验；
     * {@code agentId} 解析自事件流的 sessionID，缺失时业务上就是 null，故保留 null 语义。</p>
     *
     * @param type      事件类型，不允许为 null
     * @param message   事件描述，允许为 null
     * @param agentId   事件关联的 Agent 标识，允许为 null
     * @param timestamp 事件时间戳
     */
    public AgentEvent {
        type = Objects.requireNonNull(type, "type 不能为 null");
    }

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;
}
