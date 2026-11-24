package com.chua.common.support.ai.agent.run;

/**
 * Agent 运行步骤执行结果状态。
 *
 * @author CH
 * @since 4.0.0.52
 */
public enum AgentStepStatus {

    /**
     * 执行成功
     */
    OK,

    /**
     * 执行失败
     */
    FAILED,

    /**
     * 被跳过（条件不满足 / 上游失败级联）
     */
    SKIPPED;

    /**
     * 按名称解析步骤状态，忽略大小写；空值或未知值回退 {@link #OK}。
     *
     * @param value 状态名
     * @return 步骤状态枚举
     */
    public static AgentStepStatus of(String value) {
        if (value == null || value.isBlank()) {
            return OK;
        }
        try {
            return AgentStepStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OK;
        }
    }
}
