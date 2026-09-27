package com.chua.common.support.ai.rule;

import com.chua.common.support.ai.decision.DecisionBatch;
import com.chua.common.support.ai.decision.DecisionProvider;
import com.chua.common.support.ai.decision.DecisionQuery;
import com.chua.common.support.ai.decision.DecisionRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 规则执行器。
 *
 * <p><b>决策是可选功能，不是必需依赖</b>。用 {@link #of(RuleSet)} 构造时
 * 本类<b>完全不触碰</b> {@code ai.decision} 包：规则只做 {@code when} 条件求值。
 * 用 {@link #withDecision(RuleSet, DecisionProvider, List)} 构造时才装配决策：
 * 执行前把问题批量求值一次、展平成变量注入上下文，规则像读普通变量一样读它。
 * 两种构造方式的<b>规则定义完全相同</b>——决策只是给规则多了一批可读变量，
 * 规则作者不需要声明「这条规则要不要决策」。</p>
 *
 * <p>执行流程：</p>
 * <ol>
 *   <li>若有决策提供者，先求值一次并展平；没有则这一步整个跳过</li>
 *   <li>按 {@link RuleSet} 的顺序（salience 降序稳定）逐条求值</li>
 *   <li>条件成立（顶层 OR，无条件视为成立）则执行动作，并把规则的
 *       {@code var} 声明注入 vars 层后传入</li>
 *   <li>命中的 effect 逐个聚合，取最重的一档</li>
 *   <li>命中 {@link RuleEffect#HALT} 时<b>立即中断</b>，后续规则不再求值</li>
 *   <li>本轮有规则用 {@code to} 指定了下一条规则时，<b>只有下一条规则自己给出的
 *       {@code effect} 等于当前累积结论</b>才真的走过去；结论不一致则链断在此处。
 *       跳转沿用同一个上下文，因此目标规则能看到前一轮的全部中间结果。
 *       跳转次数受 {@link #DEFAULT_MAX_HOPS} 限制，
 *       成环会抛 {@link RuleException} 而不是无限循环</li>
 * </ol>
 *
 * <p><b>线程安全</b>：规则集不可变，决策提供者由调用方保证线程安全，
 * 每次 {@link #execute(Object, Map)} 自建独立 {@link RuleContext}。
 * 因此本类<b>可以</b>被多线程并发复用，不需要加锁。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleExecutor {

    /**
     * 规则集
     */
    private final RuleSet ruleSet;

    /**
     * 决策提供者，为 null 表示本执行器不装配决策
     */
    private final DecisionProvider decisionProvider;

    /**
     * 要向决策层提出的问题
     */
    private final List<DecisionQuery> queries;

    /**
     * 跨规则跳转的最大跳数
     */
    private final int maxHops;

    private RuleExecutor(RuleSet ruleSet, DecisionProvider decisionProvider,
                         List<DecisionQuery> queries, int maxHops) {
        this.ruleSet = ruleSet;
        this.decisionProvider = decisionProvider;
        this.queries = queries;
        this.maxHops = maxHops;
    }

    /**
     * 默认最大跳数。
     *
     * <p>8 跳足够表达「校验 → 分派 → 处理 → 兜底」这类真实跳转链，
     * 又小到能让成环（A 跳 B、B 跳回 A）在一瞬间暴露而不是把 CPU 烧掉。</p>
     */
    public static final int DEFAULT_MAX_HOPS = 8;

    /**
     * 构造<b>不装配决策</b>的执行器。
     *
     * <p>这是默认用法：规则只做条件求值，不产生任何决策调用。</p>
     *
     * @param ruleSet 规则集
     * @return 规则执行器
     * @throws NullPointerException 规则集为 null 时
     */
    public static RuleExecutor of(RuleSet ruleSet) {
        return of(ruleSet, DEFAULT_MAX_HOPS);
    }

    /**
     * 构造不装配决策的执行器，并指定最大跳数。
     *
     * @param ruleSet 规则集
     * @param maxHops 跨规则跳转的最大跳数，不可为负
     * @return 规则执行器
     * @throws NullPointerException     规则集为 null 时
     * @throws IllegalArgumentException 最大跳数为负时
     */
    public static RuleExecutor of(RuleSet ruleSet, int maxHops) {
        Objects.requireNonNull(ruleSet, "ruleSet 不能为 null");
        if (maxHops < 0) {
            throw new IllegalArgumentException("maxHops 不能为负");
        }
        return new RuleExecutor(ruleSet, null, List.of(), maxHops);
    }

    /**
     * 构造装配决策的执行器。
     *
     * <p>问题列表<b>不可为空</b>：既然选择了装配决策，就要明确问什么。
     * 允许空列表会让「配了决策但一个问题都没问」这种无效配置静默通过。</p>
     *
     * @param ruleSet          规则集
     * @param decisionProvider 决策提供者
     * @param queries          要批量求值的问题
     * @return 规则执行器
     * @throws NullPointerException     任一参数为 null 时
     * @throws IllegalArgumentException 问题列表为空时
     */
    public static RuleExecutor withDecision(RuleSet ruleSet,
                                            DecisionProvider decisionProvider,
                                            List<DecisionQuery> queries) {
        Objects.requireNonNull(ruleSet, "ruleSet 不能为 null");
        Objects.requireNonNull(decisionProvider, "decisionProvider 不能为 null");
        Objects.requireNonNull(queries, "queries 不能为 null");
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("queries 不能为空");
        }
        return new RuleExecutor(ruleSet, decisionProvider, List.copyOf(queries), DEFAULT_MAX_HOPS);
    }

    /**
     * 是否装配了决策。
     *
     * @return 装配了决策返回 true
     */
    public boolean hasDecision() {
        return decisionProvider != null;
    }

    /**
     * 执行一次规则求值。
     *
     * @param input       流程输入，可为 {@link Map} 或任意业务对象，可为 null
     * @param environment 环境量，可为 null
     * @return 执行结果
     */
    public RuleResult execute(Object input, Map<String, Object> environment) {
        Map<String, Object> external = decide(input);
        RuleContext context = RuleContext.of(null, external, input, environment);
        Acc result = new Acc();

        // 当前要评估的规则序列：首轮是整个规则集，之后是被 to 指向的单条规则
        List<Rule> pass = ruleSet.rules();
        // 跳转目标必须每轮重新收集，绝不能沿用上一轮的值：
        // A 跳到 B、B 自己没有 to 时，若沿用旧值会永远跳回 B 直至死循环。
        String nextTarget;
        for (int hop = 0; ; hop++) {
            if (hop > maxHops) {
                // 跳数用尽：说明规则之间形成了环（A 跳 B、B 跳回 A）。
                // 停下来比继续跳安全，但也可能漏掉本该命中的规则，
                // 因此显式抛出而不是静默截断——静默截断会让人以为跑完了。
                throw new RuleException("规则跳转跳数超过上限 " + maxHops
                        + "，疑似规则之间形成环。maxHops 可通过 of(set, maxHops) 调整。"
                        + "已执行: " + result.fired);
            }
            nextTarget = null;
            for (Rule rule : pass) {
                if (!matches(rule, context)) {
                    continue;
                }
                if (rule.hasActions()) {
                    bind(rule, context);
                    rule.apply(context);
                    result.fired.add(rule.name());
                } else {
                    result.matched.add(rule.name());
                }
                result.effect = RuleEffect.heavier(result.effect, rule.effect());
                if (rule.target() != null) {
                    nextTarget = rule.target();
                    result.target = nextTarget;
                }
                if (rule.effect() == RuleEffect.HALT) {
                    return new RuleResult(result.effect, result.fired, result.matched,
                            result.target, true, snapshot(context));
                }
            }
            if (nextTarget == null) {
                return new RuleResult(result.effect, result.fired, result.matched,
                        result.target, false, snapshot(context));
            }
            // 按标识解析下一条规则；目标不存在属于定义期错误，当场抛而不是静默忽略
            Rule next = ruleSet.byId(nextTarget);
            if (next == null) {
                throw new RuleException("规则 " + nextTarget + " 被指向，但它不在规则集内。"
                        + "可用规则标识: " + ruleSet.byIdIndex().keySet());
            }
            // 只有结论一致才真的走过去：目标规则自己给出的结论必须等于当前累积结论。
            // 不一致说明这条链不适用当前结果，断在这里而不是硬跳。
            if (next.effect() != result.effect) {
                return new RuleResult(result.effect, result.fired, result.matched,
                        result.target, false, snapshot(context));
            }
            pass = List.of(next);
        }
    }

    /**
     * 跨规则跳转的中间累加器。
     *
     * <p>用一个小可变对象在多轮循环间传递状态，而不是让 {@code execute}
     * 靠一串局部变量和返回值倒腾——跳转让控制流从「一层循环」变成
     * 「外层跳数 + 内层规则」两层，裸局部变量在这种结构里很容易漏掉某一处赋值。</p>
     */
    private static final class Acc {

        /**
         * 聚合效果
         */
        private RuleEffect effect = RuleEffect.NONE;

        /**
         * 执行了动作的规则名
         */
        private final List<String> fired = new ArrayList<>();

        /**
         * 只命中无动作的规则名
         */
        private final List<String> matched = new ArrayList<>();

        /**
         * 最近一次指定并跟随到的下一条规则标识
         */
        private String target;
    }

    /**
     * 用输入执行，不带环境量。
     *
     * @param input 流程输入，可为 null
     * @return 执行结果
     */
    public RuleResult execute(Object input) {
        return execute(input, null);
    }

    /**
     * 求值决策并展平为变量。
     *
     * <p>未装配决策时返回 null，上下文那一层就是空的——
     * 规则引擎对决策完全无感，这是「决策是可选功能」的落点。</p>
     */
    private Map<String, Object> decide(Object input) {
        if (decisionProvider == null) {
            return null;
        }
        DecisionRequest request = DecisionRequest.of(input, queries);
        DecisionBatch batch = decisionProvider.decide(request);
        batch.answers(request);
        return batch.flatten();
    }

    /**
     * 条件求值，无条件视为成立。
     */
    private static boolean matches(Rule rule, RuleContext context) {
        return rule.matched(context);
    }

    /**
     * 把规则声明的变量注入 vars 层。
     *
     * <p>vars 层是最高优先级，刻意盖过决策展平结果与原始输入：
     * 规则作者对自己这条规则的显式声明，是这条规则内部最权威的数据。
     * 这里只在规则<b>真的命中</b>时才注入——未命中的规则不应影响上下文。</p>
     */
    private static void bind(Rule rule, RuleContext context) {
        for (Map.Entry<String, Object> entry : rule.variables().entrySet()) {
            context.declare(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 冻结上下文变量。
     *
     * <p>把五层（输入、环境量、决策展平、规则中间结果、规则声明）拍平。
     * 拍平顺序即优先级顺序，后写入的覆盖先写入的。</p>
     */
    private static Map<String, Object> snapshot(RuleContext context) {
        Map<String, Object> all = new LinkedHashMap<>();
        if (context.input() instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                all.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        all.putAll(context.environment());
        all.putAll(context.external());
        all.putAll(context.current());
        all.putAll(context.vars());
        return Collections.unmodifiableMap(all);
    }
}
