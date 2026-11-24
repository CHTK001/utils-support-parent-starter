package com.chua.common.support.ai.agent.run;

/**
 * Agent 运行状态机。
 *
 * <p>状态流转：{@code RUNNING → WAITING_APPROVAL → RUNNING → COMPLETED | FAILED | CANCELED}。
 * 人工取消 / 预算耗尽 / 审批拒绝与超时进入 {@code CANCELED}；进入终态后不可再变更。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public enum AgentRunStatus {

    /**
     * 执行中
     */
    RUNNING,

    /**
     * 等待人工审批（高风险工具确认）
     */
    WAITING_APPROVAL,

    /**
     * 执行成功
     */
    COMPLETED,

    /**
     * 执行失败
     */
    FAILED,

    /**
     * 已取消（人工取消 / 预算耗尽 / 审批超时或拒绝）
     */
    CANCELED;

    /**
     * 是否终态（终态运行不可再变更状态）。
     *
     * @return 终态返回 true
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED;
    }

    /**
     * 按名称解析状态，忽略大小写；空值或未知值回退 {@link #RUNNING}。
     *
     * @param value 状态名
     * @return 状态枚举
     */
    public static AgentRunStatus of(String value) {
        if (value == null || value.isBlank()) {
            return RUNNING;
        }
        try {
            return AgentRunStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RUNNING;
        }
    }
}
