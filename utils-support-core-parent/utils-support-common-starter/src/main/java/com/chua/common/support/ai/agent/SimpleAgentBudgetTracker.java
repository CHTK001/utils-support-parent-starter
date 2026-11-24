package com.chua.common.support.ai.agent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link AgentBudgetTracker} 默认实现：原子计数 + 熔断闩锁。
 *
 * <p>任一维度达到 {@link AgentBudget} 上限即置位闩锁并记录原因；
 * 后续记账不再覆盖原因（首因保留）。时间预算在每次访问时惰性判定。
 * 所有方法线程安全。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public class SimpleAgentBudgetTracker implements AgentBudgetTracker {

    /**
     * unlimited() 共享单例
     */
    static final class UnlimitedHolder {
        static final SimpleAgentBudgetTracker INSTANCE =
                new SimpleAgentBudgetTracker(AgentBudget.unlimited());
    }

    /**
     * 预算规格
     */
    private final AgentBudget budget;

    /**
     * 起始时间戳（毫秒）
     */
    private final long startedAt = System.currentTimeMillis();

    /**
     * 累计输入 Token
     */
    private final AtomicLong inputTokens = new AtomicLong(0);

    /**
     * 累计输出 Token
     */
    private final AtomicLong outputTokens = new AtomicLong(0);

    /**
     * 累计工具调用次数
     */
    private final AtomicLong toolCalls = new AtomicLong(0);

    /**
     * 累计费用（微单位）
     */
    private final AtomicLong costMicros = new AtomicLong(0);

    /**
     * 熔断闩锁
     */
    private volatile boolean exceeded = false;

    /**
     * 熔断原因（首因保留）
     */
    private volatile String violationReason;

    /**
     * 构造记账器。
     *
     * @param budget 预算规格，null 视为不限
     */
    public SimpleAgentBudgetTracker(AgentBudget budget) {
        this.budget = budget == null ? AgentBudget.unlimited() : budget;
    }

    @Override
    public AgentBudget budget() {
        return budget;
    }

    @Override
    public void recordTokens(long inputTokens, long outputTokens) {
        if (inputTokens > 0) {
            this.inputTokens.addAndGet(inputTokens);
        }
        if (outputTokens > 0) {
            this.outputTokens.addAndGet(outputTokens);
        }
        if (budget.isUnlimited()) {
            return;
        }
        long usedIn = this.inputTokens.get();
        long usedOut = this.outputTokens.get();
        if (budget.maxInputTokens() > 0 && usedIn >= budget.maxInputTokens()) {
            trip("输入 Token 超限: 已用 " + usedIn + " >= 上限 " + budget.maxInputTokens());
        }
        if (budget.maxOutputTokens() > 0 && usedOut >= budget.maxOutputTokens()) {
            trip("输出 Token 超限: 已用 " + usedOut + " >= 上限 " + budget.maxOutputTokens());
        }
        if (budget.maxTotalTokens() > 0 && usedIn + usedOut >= budget.maxTotalTokens()) {
            trip("Token 总量超限: 已用 " + (usedIn + usedOut) + " >= 上限 " + budget.maxTotalTokens());
        }
    }

    @Override
    public void recordToolCall() {
        long used = toolCalls.incrementAndGet();
        if (budget.maxToolCalls() > 0 && used >= budget.maxToolCalls()) {
            trip("工具调用次数超限: 已用 " + used + " >= 上限 " + budget.maxToolCalls());
        }
    }

    @Override
    public void recordCost(long costMicros) {
        if (costMicros <= 0) {
            return;
        }
        long used = this.costMicros.addAndGet(costMicros);
        if (budget.maxCostMicros() > 0 && used >= budget.maxCostMicros()) {
            trip("费用超限: 已用 " + used + " >= 上限 " + budget.maxCostMicros() + "（微单位）");
        }
    }

    @Override
    public long usedInputTokens() {
        return inputTokens.get();
    }

    @Override
    public long usedOutputTokens() {
        return outputTokens.get();
    }

    @Override
    public long usedTotalTokens() {
        return inputTokens.get() + outputTokens.get();
    }

    @Override
    public long usedToolCalls() {
        return toolCalls.get();
    }

    @Override
    public long usedCostMicros() {
        return costMicros.get();
    }

    @Override
    public long elapsedMillis() {
        return System.currentTimeMillis() - startedAt;
    }

    @Override
    public boolean isExceeded() {
        if (exceeded) {
            return true;
        }
        if (budget.maxTimeMillis() > 0 && elapsedMillis() >= budget.maxTimeMillis()) {
            trip("运行时长超限: 已用 " + elapsedMillis() + "ms >= 上限 " + budget.maxTimeMillis() + "ms");
            return true;
        }
        return false;
    }

    @Override
    public String violationReason() {
        return exceeded ? violationReason : null;
    }

    @Override
    public void reset() {
        inputTokens.set(0);
        outputTokens.set(0);
        toolCalls.set(0);
        costMicros.set(0);
        exceeded = false;
        violationReason = null;
    }

    /**
     * 置位熔断闩锁（首因保留）。
     *
     * @param reason 熔断原因
     */
    private void trip(String reason) {
        if (!exceeded) {
            synchronized (this) {
                if (!exceeded) {
                    violationReason = reason;
                    exceeded = true;
                }
            }
        }
    }
}
