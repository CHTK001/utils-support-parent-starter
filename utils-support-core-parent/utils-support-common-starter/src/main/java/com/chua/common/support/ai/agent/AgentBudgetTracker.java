package com.chua.common.support.ai.agent;

/**
 * Agent 预算记账与熔断器。
 *
 * <p>运行期对 {@link AgentBudget} 声明的各维度做累计记账；任一维度达到上限后，
 * {@link #isExceeded()} 置位并保持（闩锁语义，除非 {@link #reset()}），
 * 执行方在模型循环 / 工具调用前检查并立即收尾，防止继续烧钱。</p>
 *
 * <p>记账来源：实现方在收到用量事件（如 {@link AgentHookEvent} 的
 * totalInputTokens / totalOutputTokens）与工具执行点回调本接口。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public interface AgentBudgetTracker {

    /**
     * 本记账器对应的预算规格。
     *
     * @return 预算规格
     */
    AgentBudget budget();

    /**
     * 记账一次模型调用的 Token 消耗。
     *
     * @param inputTokens  输入 Token 数
     * @param outputTokens 输出 Token 数
     */
    void recordTokens(long inputTokens, long outputTokens);

    /**
     * 记账一次工具调用。
     */
    void recordToolCall();

    /**
     * 记账费用（微单位）。
     *
     * @param costMicros 本次费用（微单位，正数）
     */
    void recordCost(long costMicros);

    /**
     * 已消耗输入 Token。
     *
     * @return 累计输入 Token 数
     */
    long usedInputTokens();

    /**
     * 已消耗输出 Token。
     *
     * @return 累计输出 Token 数
     */
    long usedOutputTokens();

    /**
     * 已消耗 Token 总量。
     *
     * @return 累计 Token 数（输入+输出）
     */
    long usedTotalTokens();

    /**
     * 已执行工具调用次数。
     *
     * @return 累计工具调用次数
     */
    long usedToolCalls();

    /**
     * 已记账费用。
     *
     * @return 累计费用（微单位）
     */
    long usedCostMicros();

    /**
     * 已运行时长。
     *
     * @return 从记账器创建 / 复位起的毫秒数
     */
    long elapsedMillis();

    /**
     * 是否已触发熔断（闩锁：置位后保持，直到 {@link #reset()}）。
     *
     * @return true 表示预算已超限，执行方应立即停止
     */
    boolean isExceeded();

    /**
     * 熔断原因（超限维度 + 限额 + 实际值）。
     *
     * @return 原因文本；未熔断时返回 null
     */
    String violationReason();

    /**
     * 复位记账（新一轮运行前调用）：清零累计值与熔断闩锁。
     */
    void reset();

    /**
     * 不设限制的记账器（共享单例语义）。
     *
     * <p>供未配置预算的实现兜底：所有记账操作照常累计（便于观测），
     * 但永不熔断。</p>
     *
     * @return 不熔断的记账器
     */
    static AgentBudgetTracker unlimited() {
        return SimpleAgentBudgetTracker.UnlimitedHolder.INSTANCE;
    }
}
