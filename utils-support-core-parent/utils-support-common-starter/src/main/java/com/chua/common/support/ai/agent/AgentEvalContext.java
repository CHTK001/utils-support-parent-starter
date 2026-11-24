package com.chua.common.support.ai.agent;

/**
 * 评测钩子的运行上下文。
 *
 * <p>在每次 {@link Agent#run(String)} / {@link Agent#runStream(String)} 边界
 * 采集的最小现场：谁在跑、跑什么、什么模式、何时开始。</p>
 *
 * @param agentId     Agent 标识，可为 null
 * @param sessionId   业务会话标识，可为 null
 * @param input       本次输入文本，可为 null
 * @param mode        执行模式（如 sync / stream / ORCHESTRATE_*），可为 null
 * @param startMillis 开始时间戳（毫秒）
 * @author CH
 * @since 4.0.0.52
 */
public record AgentEvalContext(
        String agentId,
        String sessionId,
        String input,
        String mode,
        long startMillis) {

    /**
     * 构造评测上下文。
     *
     * @param agentId Agent 标识，可为 null
     * @param input   输入文本，可为 null
     * @return 上下文
     */
    public static AgentEvalContext of(String agentId, String input) {
        return new AgentEvalContext(agentId, null, input, null, System.currentTimeMillis());
    }

    /**
     * 派生同现场的新上下文（换模式/会话时复用）。
     *
     * @param mode      新模式
     * @param sessionId 新会话标识
     * @return 新上下文
     */
    public AgentEvalContext withMode(String mode, String sessionId) {
        return new AgentEvalContext(agentId, sessionId, input, mode, startMillis);
    }
}
