package com.chua.common.support.rule;

import java.util.function.ToIntFunction;

/**
 * 规则定义。
 *
 * <p>一条规则由「条件（LHS）」与「动作（RHS）」两部分组成，
 * 并携带一组控制属性用于议程冲突消解。属性命名与语义对齐 Drools，
 * 便于熟悉规则引擎的开发者直接迁移。</p>
 *
 * <h3>控制属性</h3>
 * <table border="1">
 *   <caption>规则控制属性</caption>
 *   <tr><th>属性</th><th>对应 Drools</th><th>说明</th></tr>
 *   <tr><td>{@link #salience()}</td><td>salience</td>
 *       <td>优先级，值越大越先执行，默认 0</td></tr>
 *   <tr><td>{@link #agendaGroup()}</td><td>agenda-group</td>
 *       <td>议程分组，仅焦点组内规则可激活，默认 {@code MAIN}</td></tr>
 *   <tr><td>{@link #activationGroup()}</td><td>activation-group</td>
 *       <td>激活分组，同组内仅优先级最高者触发，其余取消</td></tr>
 *   <tr><td>{@link #noLoop()}</td><td>no-loop</td>
 *       <td>禁止自身 RHS 再次激活自身，默认 true</td></tr>
 *   <tr><td>{@link #lockOnActive()}</td><td>lock-on-active</td>
 *       <td>焦点期间只允许激活一次，默认 false</td></tr>
 *   <tr><td>{@link #enabled()}</td><td>enabled</td>
 *       <td>是否启用，默认 true</td></tr>
 * </table>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * Rule rule = Rule.builder("大额订单转人工")
 *         .description("金额超过 10 万的订单需要人工复核")
 *         .salience(100)
 *         .noLoop(true)
 *         .when(Pattern.of("order", OrderFact.class, o -> o.getAmount() > 100_000))
 *         .then(Actions.setResult("NEED_MANUAL_REVIEW"))
 *         .build();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class Rule {

    /**
     * 默认议程分组
     */
    public static final String DEFAULT_AGENDA_GROUP = "MAIN";

    /**
     * 规则名，同一规则库内唯一
     */
    private final String name;

    /**
     * 规则描述
     */
    private final String description;

    /**
     * 静态优先级
     */
    private final int salience;

    /**
     * 动态优先级计算器，可为 null
     */
    private final ToIntFunction<RuleContext> salienceProvider;

    /**
     * 议程分组
     */
    private final String agendaGroup;

    /**
     * 激活分组，可为 null
     */
    private final String activationGroup;

    /**
     * 是否禁止自身再激活
     */
    private final boolean noLoop;

    /**
     * 焦点期间是否只激活一次
     */
    private final boolean lockOnActive;

    /**
     * 是否启用
     */
    private final boolean enabled;

    /**
     * 条件部分
     */
    private final Condition when;

    /**
     * 动作部分
     */
    private final Action then;

    /**
     * 在规则库中的声明顺序，用于优先级相同时的稳定排序
     */
    private int sequence;

    /**
     * 创建规则。
     *
     * @param builder 构建器
     */
    private Rule(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.salience = builder.salience;
        this.salienceProvider = builder.salienceProvider;
        this.agendaGroup = builder.agendaGroup;
        this.activationGroup = builder.activationGroup;
        this.noLoop = builder.noLoop;
        this.lockOnActive = builder.lockOnActive;
        this.enabled = builder.enabled;
        this.when = builder.when;
        this.then = builder.then;
        this.sequence = builder.sequence;
    }

    /**
     * 创建规则构建器。
     *
     * @param name 规则名
     * @return 构建器
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * 获取规则名。
     *
     * @return 规则名
     */
    public String name() {
        return name;
    }

    /**
     * 获取规则描述。
     *
     * @return 规则描述，可能为 null
     */
    public String description() {
        return description;
    }

    /**
     * 获取静态优先级。
     *
     * @return 优先级
     */
    public int salience() {
        return salience;
    }

    /**
     * 计算本次激活的动态优先级。
     *
     * <p>设置了 {@code salienceProvider} 时以其结果为准，
     * 并与静态优先级相加（等价于 Drools 的动态 salience 叠加语义）。</p>
     *
     * @param context 规则上下文
     * @return 本次激活优先级
     */
    public int resolveSalience(RuleContext context) {
        if (salienceProvider == null) {
            return salience;
        }
        return salience + salienceProvider.applyAsInt(context);
    }

    /**
     * 获取议程分组。
     *
     * @return 议程分组
     */
    public String agendaGroup() {
        return agendaGroup;
    }

    /**
     * 获取激活分组。
     *
     * @return 激活分组，未设置返回 null
     */
    public String activationGroup() {
        return activationGroup;
    }

    /**
     * 是否禁止自身再激活。
     *
     * @return 禁止返回 true
     */
    public boolean noLoop() {
        return noLoop;
    }

    /**
     * 焦点期间是否只激活一次。
     *
     * @return 锁定返回 true
     */
    public boolean lockOnActive() {
        return lockOnActive;
    }

    /**
     * 是否启用。
     *
     * @return 启用返回 true
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 获取条件部分。
     *
     * @return 条件
     */
    public Condition when() {
        return when;
    }

    /**
     * 获取动作部分。
     *
     * @return 动作
     */
    public Action then() {
        return then;
    }

    /**
     * 获取声明顺序。
     *
     * @return 声明顺序
     */
    public int sequence() {
        return sequence;
    }

    /**
     * 由规则库在装配时固化声明顺序。
     *
     * @param sequence 声明顺序
     */
    void assignSequence(int sequence) {
        this.sequence = sequence;
    }

    /**
     * 规则构建器。
     */
    public static final class Builder {

        /**
         * 规则名
         */
        private final String name;

        /**
         * 规则描述
         */
        private String description;

        /**
         * 静态优先级
         */
        private int salience;

        /**
         * 动态优先级计算器
         */
        private ToIntFunction<RuleContext> salienceProvider;

        /**
         * 议程分组
         */
        private String agendaGroup = DEFAULT_AGENDA_GROUP;

        /**
         * 激活分组
         */
        private String activationGroup;

        /**
         * 是否禁止自身再激活
         */
        private boolean noLoop = true;

        /**
         * 焦点期间是否只激活一次
         */
        private boolean lockOnActive;

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 条件部分
         */
        private Condition when;

        /**
         * 动作部分
         */
        private Action then;

        /**
         * 声明顺序
         */
        private int sequence;

        /**
         * 创建构建器。
         *
         * @param name 规则名
         */
        private Builder(String name) {
            if (name == null || name.isBlank()) {
                throw new RuleException("规则名不能为空");
            }
            this.name = name.trim();
        }

        /**
         * 设置规则描述。
         *
         * @param description 规则描述
         * @return 当前构建器
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 设置静态优先级。
         *
         * @param salience 优先级，值越大越先执行
         * @return 当前构建器
         */
        public Builder salience(int salience) {
            this.salience = salience;
            return this;
        }

        /**
         * 设置动态优先级计算器。
         *
         * @param salienceProvider 动态优先级计算器
         * @return 当前构建器
         */
        public Builder salienceProvider(ToIntFunction<RuleContext> salienceProvider) {
            this.salienceProvider = salienceProvider;
            return this;
        }

        /**
         * 设置议程分组。
         *
         * @param agendaGroup 议程分组，null 或空白回退为 {@code MAIN}
         * @return 当前构建器
         */
        public Builder agendaGroup(String agendaGroup) {
            this.agendaGroup = (agendaGroup == null || agendaGroup.isBlank())
                    ? DEFAULT_AGENDA_GROUP : agendaGroup.trim();
            return this;
        }

        /**
         * 设置激活分组。
         *
         * <p>同一激活分组内，只有优先级最高的规则会被触发，
         * 其余同组规则从议程取消。</p>
         *
         * @param activationGroup 激活分组，null 表示不启用
         * @return 当前构建器
         */
        public Builder activationGroup(String activationGroup) {
            this.activationGroup = (activationGroup == null || activationGroup.isBlank())
                    ? null : activationGroup.trim();
            return this;
        }

        /**
         * 设置是否禁止自身再激活。
         *
         * @param noLoop true 禁止
         * @return 当前构建器
         */
        public Builder noLoop(boolean noLoop) {
            this.noLoop = noLoop;
            return this;
        }

        /**
         * 设置焦点期间是否只激活一次。
         *
         * @param lockOnActive true 锁定
         * @return 当前构建器
         */
        public Builder lockOnActive(boolean lockOnActive) {
            this.lockOnActive = lockOnActive;
            return this;
        }

        /**
         * 设置是否启用。
         *
         * @param enabled true 启用
         * @return 当前构建器
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * 设置条件部分。
         *
         * @param when 条件
         * @return 当前构建器
         */
        public Builder when(Condition when) {
            this.when = when;
            return this;
        }

        /**
         * 设置动作部分。
         *
         * @param then 动作
         * @return 当前构建器
         */
        public Builder then(Action then) {
            this.then = then;
            return this;
        }

        /**
         * 设置声明顺序。
         *
         * @param sequence 声明顺序
         * @return 当前构建器
         */
        public Builder sequence(int sequence) {
            this.sequence = sequence;
            return this;
        }

        /**
         * 构建规则。
         *
         * @return 规则实例
         * @throws RuleException 缺少条件或动作时抛出
         */
        public Rule build() {
            if (when == null) {
                throw new RuleException("规则[" + name + "]缺少条件部分");
            }
            if (then == null) {
                throw new RuleException("规则[" + name + "]缺少动作部分");
            }
            return new Rule(this);
        }
    }

    /**
     * 列出规则的静态元信息，便于调试与文档生成。
     *
     * @return 元信息行
     */
    public String describe() {
        return "Rule[" + name
                + ", salience=" + salience
                + ", agendaGroup=" + agendaGroup
                + ", activationGroup=" + activationGroup
                + ", noLoop=" + noLoop
                + ", lockOnActive=" + lockOnActive
                + ", enabled=" + enabled
                + ", sequence=" + sequence
                + "]";
    }
}
