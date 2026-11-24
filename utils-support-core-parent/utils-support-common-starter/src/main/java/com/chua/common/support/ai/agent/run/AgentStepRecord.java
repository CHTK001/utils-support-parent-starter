package com.chua.common.support.ai.agent.run;

/**
 * Agent 运行步骤记录（时间线上的单步）。
 *
 * <p>字段与 ai-starter 的 {@code sys_ai_agent_step} 表同构。步骤在动作完成后落库，
 * 摘要类字段由写入方截断（单条 ≤2KB），避免长输出撑爆存储。</p>
 *
 * @param stepId        步骤标识（UUIDv7）
 * @param runId         所属运行标识
 * @param seq           步骤序号（run 内从 0 递增）
 * @param type          步骤类型
 * @param name          工具名 / 子代理名 / 节点名
 * @param argumentsJson 参数摘要（JSON，截断）
 * @param resultSummary 结果摘要（截断；失败时存失败原因）
 * @param status        步骤执行状态
 * @param tokens        本步 token 消耗
 * @param startedAt     开始时间（epoch 毫秒）
 * @param endedAt       结束时间（epoch 毫秒）
 * @author CH
 * @since 4.0.0.52
 */
public record AgentStepRecord(
        String stepId,
        String runId,
        int seq,
        AgentStepType type,
        String name,
        String argumentsJson,
        String resultSummary,
        AgentStepStatus status,
        long tokens,
        long startedAt,
        Long endedAt) {

    /**
     * 紧凑构造器：规范必填字段与缺省值。
     */
    public AgentStepRecord {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId 不能为空");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        type = type == null ? AgentStepType.REASONING : type;
        status = status == null ? AgentStepStatus.OK : status;
    }
}
