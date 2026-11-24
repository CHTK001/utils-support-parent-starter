package com.chua.common.support.ai.agent;

import com.chua.common.support.ai.AiUsage;

/**
 * 评测钩子的运行结果记录。
 *
 * <p>一次 Agent 运行的完整观测：上下文 + 输出 + 成败 + 耗时 + 用量。
 * 由 {@link AgentEvalHook#onRunEnd} 接收，可落库、上报或断言。</p>
 *
 * @param context        运行上下文，不应为 null
 * @param output         输出文本，可为 null（失败时可能无输出）
 * @param success        是否成功结束
 * @param error          失败原因（成功时为 null）
 * @param durationMillis 耗时（毫秒）
 * @param usage          用量信息，可为 null
 * @author CH
 * @since 4.0.0.52
 */
public record AgentEvalRecord(
        AgentEvalContext context,
        String output,
        boolean success,
        String error,
        long durationMillis,
        AiUsage usage) {

    /**
     * 构造成功记录。
     *
     * @param context        上下文
     * @param output         输出
     * @param durationMillis 耗时
     * @param usage          用量
     * @return 成功记录
     */
    public static AgentEvalRecord success(AgentEvalContext context, String output,
                                          long durationMillis, AiUsage usage) {
        return new AgentEvalRecord(context, output, true, null, durationMillis, usage);
    }

    /**
     * 构造失败记录。
     *
     * @param context        上下文
     * @param error          失败原因
     * @param durationMillis 耗时
     * @return 失败记录
     */
    public static AgentEvalRecord failure(AgentEvalContext context, String error,
                                          long durationMillis) {
        return new AgentEvalRecord(context, null, false, error, durationMillis, null);
    }
}
