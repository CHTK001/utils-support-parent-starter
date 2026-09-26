package com.chua.common.support.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 规则路由构造器：把「条件 → handler」的 if / else-if / else 链编译成一组等价规则。
 *
 * <p><b>解决的问题。</b>{@link Rule} 一条规则只能带一个动作，因此
 * {@code when(A).then(x)} 这种单分支写法与直接调用无差别，真正需要路由的场景是
 * 「多条条件各带各的动作，且要求第一个命中的生效」。本类即为此而设。</p>
 *
 * <pre>{@code
 * RuleRoute.builder("风控路由")
 *         .description("按金额与 VIP 分流")
 *         .when(Pattern.of("order", OrderFact.class, o -> o.getAmount() > 100_000L))
 *         .to(Actions.deny())
 *         .when(Pattern.of("order", OrderFact.class, o -> o.isVip()))
 *         .to(Actions.setResult("REVIEW"))
 *         .then(Actions.allow())                 // 兜底，语义等同 else
 *         .build();                                // -> List<Rule>
 * }</pre>
 *
 * <p><b>编译规则。</b>为精确还原 if / else-if 语义（多个条件同时成立时
 * <b>只执行第一个</b>），第 <i>i</i> 个分支被编译为
 * {@code not(C1) and ... and not(Ci-1) and Ci}，兜底分支被编译为
 * {@code not(C1) and ... and not(Cn)}。这样各分支互斥，
 * 无需引擎新增「路由」概念，也无需在 RHS 二次求值。</p>
 *
 * <p><b>约束。</b></p>
 * <ul>
 *   <li>{@code when} 必须以 {@code to(handler)} 收尾，未收尾即 {@code build()} 抛异常；</li>
 *   <li>{@code then} 最多调用一次，重复调用立即抛异常；</li>
 *   <li>至少需要一个 {@code when} 或一个 {@code then}。</li>
 * </ul>
 *
 * <p>本类为纯构造期工具，生成的是普通 {@link Rule}，可直接交给
 * {@link RuleBase#of(Rule...)} 与 {@link RuleBreaker}，无额外运行时依赖。</p>
 *
 * @author chua
 */
public final class RuleRoute {

    /**
     * 工具类，禁止实例化。
     */
    private RuleRoute() {
    }

    /**
     * 创建路由构造器。
     *
     * @param name 路由名，将作为生成规则名的前缀
     * @return 构造器
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * 路由构造器。
     *
     * <p>非线程安全，仅供单线程构造期使用。</p>
     */
    public static final class Builder {

        /**
         * 路由名。
         */
        private final String name;

        /**
         * 已闭合的分支，顺序即书写顺序。
         */
        private final List<Branch> branches = new ArrayList<>();

        /**
         * 尚未调用 {@code to(handler)} 的 when 作用域。
         */
        private final List<WhenScope> pending = new ArrayList<>();

        /**
         * 兜底动作，对应 else。
         */
        private Action otherwise;

        /**
         * 规则描述，将复制到每条生成规则。
         */
        private String description;

        /**
         * 规则优先级，将复制到每条生成规则。
         */
        private int salience;

        /**
         * 动态优先级提供者，将复制到每条生成规则。
         */
        private ToIntFunction<RuleContext> salienceProvider;

        /**
         * 议程分组，将复制到每条生成规则。
         */
        private String agendaGroup;

        /**
         * 激活组，同组内只允许一条激活，将复制到每条生成规则。
         */
        private String activationGroup;

        /**
         * 是否禁止自我重复激活，将复制到每条生成规则。
         */
        private boolean noLoop;

        /**
         * 是否在激活后锁定焦点，将复制到每条生成规则。
         */
        private boolean lockOnActive;

        /**
         * 是否启用，将复制到每条生成规则。
         */
        private boolean enabled = true;

        private Builder(String name) {
            if (name == null || name.isBlank()) {
                throw new RuleException("路由名不能为空");
            }
            this.name = name.trim();
        }

        /**
         * 设置规则描述，复制到每条生成规则。
         *
         * @param description 描述
         * @return 当前构造器
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 设置固定优先级，复制到每条生成规则。
         *
         * @param salience 优先级，数值越大越先触发
         * @return 当前构造器
         */
        public Builder salience(int salience) {
            this.salience = salience;
            return this;
        }

        /**
         * 设置动态优先级提供者，复制到每条生成规则。
         *
         * @param salienceProvider 优先级提供者
         * @return 当前构造器
         */
        public Builder salienceProvider(ToIntFunction<RuleContext> salienceProvider) {
            this.salienceProvider = salienceProvider;
            return this;
        }

        /**
         * 设置议程分组，复制到每条生成规则。
         *
         * @param agendaGroup 议程分组
         * @return 当前构造器
         */
        public Builder agendaGroup(String agendaGroup) {
            this.agendaGroup = agendaGroup;
            return this;
        }

        /**
         * 设置激活组，复制到每条生成规则。
         *
         * @param activationGroup 激活组
         * @return 当前构造器
         */
        public Builder activationGroup(String activationGroup) {
            this.activationGroup = activationGroup;
            return this;
        }

        /**
         * 设置是否禁止自我重复激活，复制到每条生成规则。
         *
         * @param noLoop 是否禁止
         * @return 当前构造器
         */
        public Builder noLoop(boolean noLoop) {
            this.noLoop = noLoop;
            return this;
        }

        /**
         * 设置是否在激活后锁定焦点，复制到每条生成规则。
         *
         * @param lockOnActive 是否锁定
         * @return 当前构造器
         */
        public Builder lockOnActive(boolean lockOnActive) {
            this.lockOnActive = lockOnActive;
            return this;
        }

        /**
         * 设置是否启用，复制到每条生成规则。
         *
         * @param enabled 是否启用
         * @return 当前构造器
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * 进入条件构造。
         *
         * <p>返回的作用域对象代表「一条待绑定 handler 的条件」，
         * 必须调用 {@link WhenScope#to(Action)} 才能回到本构造器。</p>
         *
         * @param condition 触发条件
         * @return 条件作用域
         */
        public WhenScope when(Condition condition) {
            if (condition == null) {
                throw new RuleException("路由[" + name + "] when 条件不能为 null");
            }
            WhenScope scope = new WhenScope(this, condition);
            pending.add(scope);
            return scope;
        }

        /**
         * 设置兜底动作，语义等同 else。
         *
         * <p>仅在没有任何 {@code when} 成立时执行，最多允许设置一次。</p>
         *
         * @param action 兜底动作
         * @return 当前构造器
         */
        public Builder then(Action action) {
            if (action == null) {
                throw new RuleException("路由[" + name + "] then 兜底动作不能为 null");
            }
            if (otherwise != null) {
                throw new RuleException("路由[" + name + "] then 兜底动作只能设置一次");
            }
            this.otherwise = action;
            return this;
        }

        /**
         * 编译为规则列表。
         *
         * <p>分支顺序即书写顺序；每个分支都会自动排除其之前所有分支，
         * 因此多个条件同时成立时只执行第一个。</p>
         *
         * @return 生成规则列表，只读
         * @throws RuleException 存在未调用 to 的 when，或未设置任何分支与兜底时抛出
         */
        public List<Rule> build() {
            if (!pending.isEmpty()) {
                throw new RuleException("路由[" + name + "] 存在 " + pending.size()
                        + " 个 when 未通过 to(handler) 指定处理器");
            }
            if (branches.isEmpty() && otherwise == null) {
                throw new RuleException("路由[" + name + "] 至少需要一个 when 或一个 then");
            }
            List<Rule> rules = new ArrayList<>(branches.size() + 1);
            List<Condition> negated = new ArrayList<>(branches.size());
            for (int i = 0; i < branches.size(); i++) {
                Branch branch = branches.get(i);
                List<Condition> parts = new ArrayList<>(negated.size() + 1);
                for (Condition earlier : negated) {
                    parts.add(Conditions.not(earlier));
                }
                parts.add(branch.condition());
                rules.add(newRule(name + "#" + (i + 1), Conditions.and(parts), branch.action()));
                negated.add(branch.condition());
            }
            if (otherwise != null) {
                List<Condition> parts = new ArrayList<>(negated.size());
                for (Condition earlier : negated) {
                    parts.add(Conditions.not(earlier));
                }
                rules.add(newRule(name + "#else",
                        parts.isEmpty() ? Conditions.alwaysTrue() : Conditions.and(parts),
                        otherwise));
            }
            return Collections.unmodifiableList(rules);
        }

        /**
         * 编译为规则库。
         *
         * @return 规则库
         * @throws RuleException 同 {@link #build()}
         */
        public RuleBase buildBase() {
            return RuleBase.of(build().toArray(new Rule[0]));
        }

        /**
         * 记录一个已闭合分支。
         *
         * @param condition 触发条件
         * @param action    处理器
         */
        private void addBranch(Condition condition, Action action) {
            branches.add(new Branch(condition, action));
        }

        /**
         * 移除一个已闭合的 when 作用域。
         *
         * @param scope 条件作用域
         */
        private void closeScope(WhenScope scope) {
            if (!pending.remove(scope)) {
                throw new RuleException("路由[" + name + "] 该 when 未处于待绑定状态");
            }
        }

        /**
         * 按路由级设置创建一条生成规则。
         *
         * @param ruleName  规则名
         * @param condition 触发条件
         * @param action    处理器
         * @return 生成规则
         */
        private Rule newRule(String ruleName, Condition condition, Action action) {
            Rule.Builder builder = Rule.builder(ruleName)
                    .when(condition)
                    .then(action)
                    .noLoop(noLoop)
                    .lockOnActive(lockOnActive)
                    .enabled(enabled);
            if (description != null) {
                builder.description(description);
            }
            if (salienceProvider != null) {
                builder.salienceProvider(salienceProvider);
            } else if (salience != 0) {
                builder.salience(salience);
            }
            if (agendaGroup != null) {
                builder.agendaGroup(agendaGroup);
            }
            if (activationGroup != null) {
                builder.activationGroup(activationGroup);
            }
            return builder.build();
        }
    }

    /**
     * 条件作用域：代表一条「已写条件、尚未绑定 handler」的分支。
     *
     * <p>由 {@link Builder#when(Condition)} 创建，
     * 只有 {@link #to(Action)} 能把它交还给上层构造器。</p>
     */
    public static final class WhenScope {

        /**
         * 上层构造器。
         */
        private final Builder parent;

        /**
         * 触发条件。
         */
        private final Condition condition;

        /**
         * 是否已绑定 handler。
         */
        private boolean bound;

        private WhenScope(Builder parent, Condition condition) {
            this.parent = parent;
            this.condition = condition;
        }

        /**
         * 绑定处理器并回到上层构造器。
         *
         * @param action 处理器
         * @return 上层构造器
         * @throws RuleException 重复绑定时抛出
         */
        public Builder to(Action action) {
            if (action == null) {
                throw new RuleException("路由[" + parent.name + "] to 的处理器不能为 null");
            }
            if (bound) {
                throw new RuleException("路由[" + parent.name + "] 该 when 已通过 to 指定过处理器");
            }
            bound = true;
            parent.closeScope(this);
            parent.addBranch(condition, action);
            return parent;
        }
    }

    /**
     * 一个已绑定处理器的分支。
     *
     * @param condition 触发条件
     * @param action    处理器
     */
    private record Branch(Condition condition, Action action) {
    }
}
