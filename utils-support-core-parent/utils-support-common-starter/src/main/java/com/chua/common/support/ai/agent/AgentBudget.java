package com.chua.common.support.ai.agent;

/**
 * Agent 执行预算规格（不可变）。
 *
 * <p>约束一次运行可消耗的资源上限，任一维度超限即触发熔断（circuit breaker）：
 * 执行方在循环点检查 {@link AgentBudgetTracker#isExceeded()}，命中后立即停止
 * 继续调用模型 / 工具，并以带原因的响应收尾。各维度 {@code <= 0} 表示不限制。</p>
 *
 * <p>费用单位使用「微单位」（micros，如微元 / 万分之一分），避免浮点累计误差；
 * 由调用方按所用定价换算后记账。</p>
 *
 * @param maxTotalTokens  累计 Token 总量上限（输入+输出），0 表示不限
 * @param maxInputTokens  累计输入 Token 上限，0 表示不限
 * @param maxOutputTokens 累计输出 Token 上限，0 表示不限
 * @param maxToolCalls    累计工具调用次数上限，0 表示不限
 * @param maxCostMicros   累计费用上限（微单位），0 表示不限
 * @param maxTimeMillis   运行时长上限（毫秒），0 表示不限
 * @author CH
 * @since 4.0.0.52
 */
public record AgentBudget(
        long maxTotalTokens,
        long maxInputTokens,
        long maxOutputTokens,
        long maxToolCalls,
        long maxCostMicros,
        long maxTimeMillis) {

    /**
     * 构造预算规格（负值归零，视为不限）。
     */
    public AgentBudget {
        maxTotalTokens = Math.max(maxTotalTokens, 0);
        maxInputTokens = Math.max(maxInputTokens, 0);
        maxOutputTokens = Math.max(maxOutputTokens, 0);
        maxToolCalls = Math.max(maxToolCalls, 0);
        maxCostMicros = Math.max(maxCostMicros, 0);
        maxTimeMillis = Math.max(maxTimeMillis, 0);
    }

    /**
     * 不设任何限制的预算。
     *
     * @return 全维度不限的预算规格
     */
    public static AgentBudget unlimited() {
        return new AgentBudget(0, 0, 0, 0, 0, 0);
    }

    /**
     * 是否全维度不限。
     *
     * @return true 表示没有任何限制
     */
    public boolean isUnlimited() {
        return maxTotalTokens <= 0 && maxInputTokens <= 0 && maxOutputTokens <= 0
                && maxToolCalls <= 0 && maxCostMicros <= 0 && maxTimeMillis <= 0;
    }

    /**
     * 是否限制了任何维度。
     *
     * @return true 表示至少限制了一个维度
     */
    public boolean isBounded() {
        return !isUnlimited();
    }
}
