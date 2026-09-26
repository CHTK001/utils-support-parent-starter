package com.chua.common.support.rule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则库（Production Memory）。
 *
 * <p>Drools 中存放规则的位置。规则库一旦构建完成即不可变，
 * 因此可被多线程共享并长期复用；每次推理通过创建
 * {@link RuleSession} 获得独立的工作内存，互不干扰。</p>
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li>规则库只负责「有哪些规则」</li>
 *   <li>会话负责「当前有哪些事实、触发了哪些规则」</li>
 *   <li>构建时校验规则名唯一性，并按声明顺序固化排序序号</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * RuleBase ruleBase = RuleBase.builder()
 *         .add(highValueRule)
 *         .add(blockedRule)
 *         .build();
 *
 * try (RuleSession session = ruleBase.newSession()) {
 *     session.insert(new OrderFact("A100", 200_000));
 *     session.fire();
 *     Object decision = session.result();
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleBase {

    /**
     * 规则列表，按声明顺序
     */
    private final List<Rule> rules;

    /**
     * 规则名索引
     */
    private final Map<String, Rule> ruleIndex;

    /**
     * 创建规则库。
     *
     * @param rules 规则列表
     */
    private RuleBase(List<Rule> rules) {
        Map<String, Rule> index = new LinkedHashMap<>(Math.max(16, rules.size() * 2));
        int sequence = 0;
        List<Rule> ordered = new ArrayList<>(rules.size());
        for (Rule rule : rules) {
            if (index.put(rule.name(), rule) != null) {
                throw new RuleException("规则名重复：" + rule.name());
            }
            sequence++;
            rule.assignSequence(sequence);
            ordered.add(rule);
        }
        this.rules = Collections.unmodifiableList(ordered);
        this.ruleIndex = Collections.unmodifiableMap(index);
    }

    /**
     * 创建规则库构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 直接由规则数组创建规则库。
     *
     * @param rules 规则数组
     * @return 规则库
     */
    public static RuleBase of(Rule... rules) {
        return builder().add(rules).build();
    }

    /**
     * 获取全部规则。
     *
     * @return 规则列表，只读
     */
    public List<Rule> rules() {
        return rules;
    }

    /**
     * 按名称获取规则。
     *
     * @param name 规则名
     * @return 规则，不存在返回 null
     */
    public Rule rule(String name) {
        return name == null ? null : ruleIndex.get(name);
    }

    /**
     * 获取规则数量。
     *
     * @return 规则数量
     */
    public int size() {
        return rules.size();
    }

    /**
     * 获取全部规则名。
     *
     * @return 规则名集合，只读
     */
    public Set<String> ruleNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(ruleIndex.keySet()));
    }

    /**
     * 创建规则会话（使用默认配置）。
     *
     * @return 规则会话
     */
    public RuleSession newSession() {
        return new RuleSession(this);
    }

    /**
     * 创建规则会话。
     *
     * @param config 会话配置
     * @return 规则会话
     */
    public RuleSession newSession(RuleSessionConfig config) {
        return new RuleSession(this, config);
    }

    /**
     * 创建规则会话。
     *
     * @param config    会话配置
     * @param globals   全局变量，可为 null
     * @param listeners 事件监听器，可为 null
     * @return 规则会话
     */
    public RuleSession newSession(RuleSessionConfig config, Map<String, Object> globals,
                                 List<RuleListener> listeners) {
        return new RuleSession(this, config, globals, listeners);
    }

    /**
     * 基于当前规则库创建构建器，便于追加规则派生新规则库。
     *
     * @return 构建器，已预置当前全部规则
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.rules.addAll(this.rules);
        return builder;
    }

    /**
     * 规则库构建器。
     */
    public static final class Builder {

        /**
         * 规则列表
         */
        private final List<Rule> rules = new ArrayList<>();

        /**
         * 创建构建器。
         */
        private Builder() {
        }

        /**
         * 追加规则。
         *
         * @param rule 规则
         * @return 当前构建器
         */
        public Builder add(Rule rule) {
            if (rule == null) {
                throw new RuleException("规则不能为 null");
            }
            rules.add(rule);
            return this;
        }

        /**
         * 批量追加规则。
         *
         * @param rules 规则数组
         * @return 当前构建器
         */
        public Builder add(Rule... rules) {
            if (rules == null) {
                return this;
            }
            for (Rule rule : rules) {
                add(rule);
            }
            return this;
        }

        /**
         * 批量追加规则。
         *
         * @param rules 规则集合
         * @return 当前构建器
         */
        public Builder addAll(Collection<Rule> rules) {
            if (rules == null) {
                return this;
            }
            for (Rule rule : rules) {
                add(rule);
            }
            return this;
        }

        /**
         * 构建规则库。
         *
         * @return 规则库
         * @throws RuleException 规则为空或规则名重复时抛出
         */
        public RuleBase build() {
            if (rules.isEmpty()) {
                throw new RuleException("规则库不能为空");
            }
            return new RuleBase(List.copyOf(rules));
        }
    }
}
