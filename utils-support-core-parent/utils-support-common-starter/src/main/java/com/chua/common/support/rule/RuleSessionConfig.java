package com.chua.common.support.rule;

/**
 * 规则会话配置。
 *
 * <p>控制单次推理的边界与容错策略。规则之间可能通过
 * 「修改事实 → 再次触发规则」形成推理链，理论上可无限循环，
 * 因此必须设置硬性上限。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * RuleSessionConfig config = RuleSessionConfig.builder()
 *         .maxCycles(50)
 *         .maxActivationsPerCycle(1_000)
 *         .stopOnRuleFailure(false)
 *         .build();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleSessionConfig {

    /**
     * 默认最大推理轮次
     */
    public static final int DEFAULT_MAX_CYCLES = 100;

    /**
     * 默认单轮最大激活数
     */
    public static final int DEFAULT_MAX_ACTIVATIONS_PER_CYCLE = 10_000;

    /**
     * 最大推理轮次
     */
    private final int maxCycles;

    /**
     * 单轮最大激活数
     */
    private final int maxActivationsPerCycle;

    /**
     * 规则失败时是否终止推理
     */
    private final boolean stopOnRuleFailure;

    /**
     * 创建配置。
     *
     * @param maxCycles                 最大推理轮次
     * @param maxActivationsPerCycle    单轮最大激活数
     * @param stopOnRuleFailure         规则失败时是否终止
     */
    private RuleSessionConfig(int maxCycles, int maxActivationsPerCycle, boolean stopOnRuleFailure) {
        if (maxCycles <= 0) {
            throw new RuleException("最大推理轮次必须大于 0");
        }
        if (maxActivationsPerCycle <= 0) {
            throw new RuleException("单轮最大激活数必须大于 0");
        }
        this.maxCycles = maxCycles;
        this.maxActivationsPerCycle = maxActivationsPerCycle;
        this.stopOnRuleFailure = stopOnRuleFailure;
    }

    /**
     * 创建默认配置。
     *
     * @return 默认配置
     */
    public static RuleSessionConfig defaultConfig() {
        return builder().build();
    }

    /**
     * 创建配置构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取最大推理轮次。
     *
     * @return 最大推理轮次
     */
    public int maxCycles() {
        return maxCycles;
    }

    /**
     * 获取单轮最大激活数。
     *
     * @return 单轮最大激活数
     */
    public int maxActivationsPerCycle() {
        return maxActivationsPerCycle;
    }

    /**
     * 规则失败时是否终止推理。
     *
     * @return 终止返回 true
     */
    public boolean stopOnRuleFailure() {
        return stopOnRuleFailure;
    }

    /**
     * 配置构建器。
     */
    public static final class Builder {

        /**
         * 最大推理轮次
         */
        private int maxCycles = DEFAULT_MAX_CYCLES;

        /**
         * 单轮最大激活数
         */
        private int maxActivationsPerCycle = DEFAULT_MAX_ACTIVATIONS_PER_CYCLE;

        /**
         * 规则失败时是否终止
         */
        private boolean stopOnRuleFailure;

        /**
         * 创建构建器。
         */
        private Builder() {
        }

        /**
         * 设置最大推理轮次。
         *
         * @param maxCycles 最大推理轮次，必须大于 0
         * @return 当前构建器
         */
        public Builder maxCycles(int maxCycles) {
            this.maxCycles = maxCycles;
            return this;
        }

        /**
         * 设置单轮最大激活数。
         *
         * @param maxActivationsPerCycle 单轮最大激活数，必须大于 0
         * @return 当前构建器
         */
        public Builder maxActivationsPerCycle(int maxActivationsPerCycle) {
            this.maxActivationsPerCycle = maxActivationsPerCycle;
            return this;
        }

        /**
         * 设置规则失败时是否终止推理。
         *
         * @param stopOnRuleFailure true 终止
         * @return 当前构建器
         */
        public Builder stopOnRuleFailure(boolean stopOnRuleFailure) {
            this.stopOnRuleFailure = stopOnRuleFailure;
            return this;
        }

        /**
         * 构建配置。
         *
         * @return 配置实例
         */
        public RuleSessionConfig build() {
            return new RuleSessionConfig(maxCycles, maxActivationsPerCycle, stopOnRuleFailure);
        }
    }
}
