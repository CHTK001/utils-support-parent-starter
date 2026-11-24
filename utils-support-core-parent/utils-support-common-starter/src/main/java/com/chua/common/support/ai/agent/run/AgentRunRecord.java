package com.chua.common.support.ai.agent.run;

/**
 * Agent 运行记录（一次执行的聚合视图）。
 *
 * <p>一条 Run 对应一次 Agent 执行（会话内调用 / 手动调用 / 编排运行 / 定时 / 评测），
 * 下辖有序步骤 {@link AgentStepRecord}。字段与 ai-starter 的 {@code sys_ai_agent_run}
 * 表同构；本契约只描述数据，不携带行为。实现层（JDBC / 内存）与业务层共同消费。</p>
 *
 * @param runId         运行标识（UUIDv7）
 * @param sessionId     关联会话标识；null 表示后台运行（编排 / 定时 / 评测）
 * @param agentId       Agent 标识（sys_ai_agent.agent_id）
 * @param triggerType   触发来源：chat / manual / orchestration / schedule / eval
 * @param mode          执行模式：SINGLE / ROUTER / PIPELINE / FAN_OUT 等
 * @param status        运行状态
 * @param inputSummary  输入摘要（截断）
 * @param outputSummary 输出摘要（截断）
 * @param error         失败原因（成功为 null）
 * @param tokensUsed    累计 token 消耗
 * @param rounds        累计轮次
 * @param toolCalls     累计工具调用次数
 * @param subSessionId  关联的会话子代理记录（sys_ai_session_sub_agent），可为 null
 * @param userId        归属用户
 * @param startedAt     开始时间（epoch 毫秒）
 * @param endedAt       结束时间（epoch 毫秒）；运行中为 null
 * @author CH
 * @since 4.0.0.52
 */
public record AgentRunRecord(
        String runId,
        Long sessionId,
        String agentId,
        String triggerType,
        String mode,
        AgentRunStatus status,
        String inputSummary,
        String outputSummary,
        String error,
        long tokensUsed,
        int rounds,
        int toolCalls,
        Long subSessionId,
        String userId,
        long startedAt,
        Long endedAt) {

    /**
     * 紧凑构造器：规范必填字段与缺省值。
     */
    public AgentRunRecord {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
        triggerType = triggerType == null || triggerType.isBlank() ? "manual" : triggerType;
        status = status == null ? AgentRunStatus.RUNNING : status;
    }

    /**
     * 是否终态运行。
     *
     * @return 终态返回 true
     */
    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }
}
