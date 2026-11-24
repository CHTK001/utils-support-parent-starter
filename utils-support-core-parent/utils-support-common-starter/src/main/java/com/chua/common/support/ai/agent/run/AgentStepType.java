package com.chua.common.support.ai.agent.run;

/**
 * Agent 运行步骤类型。
 *
 * <p>一次运行的步骤时间线由本类型区分展示语义；与 ai-starter 的
 * {@code sys_ai_agent_step.type} 列同构。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public enum AgentStepType {

    /**
     * 思考 / 消息步骤
     */
    REASONING,

    /**
     * 工具调用（name=工具名，arguments=参数摘要，result=结果摘要）
     */
    TOOL_CALL,

    /**
     * 规划快照（计划步骤）
     */
    PLAN,

    /**
     * 子代理委派（name=子代理名，关联 sys_ai_session_sub_agent）
     */
    SUB_AGENT,

    /**
     * 错误步骤
     */
    ERROR;

    /**
     * 按名称解析步骤类型，忽略大小写；空值或未知值回退 {@link #REASONING}。
     *
     * @param value 类型名
     * @return 步骤类型枚举
     */
    public static AgentStepType of(String value) {
        if (value == null || value.isBlank()) {
            return REASONING;
        }
        try {
            return AgentStepType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return REASONING;
        }
    }
}
