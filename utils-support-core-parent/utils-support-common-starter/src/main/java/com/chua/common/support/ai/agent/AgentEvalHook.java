package com.chua.common.support.ai.agent;

/**
 * Agent 评测 / 回放钩子契约。
 *
 * <p>在 Agent 运行边界（开始 / 结束）埋点，用于评测打分、回归对比、
 * 回放采集与审计。实现方只读观测数据，不应阻塞或改变运行结果；
 * 钩子抛出的异常由调用方吞掉并记日志，绝不影响主流程。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public interface AgentEvalHook {

    /**
     * 运行开始。
     *
     * @param context 运行上下文
     */
    default void onRunStart(AgentEvalContext context) {
        // 默认不处理
    }

    /**
     * 运行结束。
     *
     * @param record 运行结果记录
     */
    default void onRunEnd(AgentEvalRecord record) {
        // 默认不处理
    }

    /**
     * 空钩子（默认兜底）。
     *
     * @return 空实现
     */
    static AgentEvalHook noop() {
        return new AgentEvalHook() {
        };
    }
}
