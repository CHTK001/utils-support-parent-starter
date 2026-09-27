package com.chua.common.support.ai.rule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一组按优先级排好序的规则。
 *
 * <p>排序规则：{@link Rule#salience()} <b>降序</b>，同 salience 保持
 * <b>声明顺序</b>（稳定排序）。稳定是必须的——同一优先级下两条规则
 * 谁先跑会改变 {@code then} 的写入顺序，进而改变 {@code current} 层里
 * 变量的最终值。不稳定排序会让规则集的行为随 JVM 实现而变。</p>
 *
 * <p>规则集本身不可变。{@link RuleExecutor} 可以被任意线程并发复用，
 * 每次 {@code execute} 自带独立上下文。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleSet {

    /**
     * 已排序的规则
     */
    private final List<Rule> rules;

    /**
     * 用规则集合构造。
     *
     * @param rules 规则集合，不可为 null、不可为空、规则标识不可重复
     * @throws NullPointerException     规则集合为 null 或含 null 元素时
     * @throws IllegalArgumentException 规则集合为空或存在重复标识时
     */
    public RuleSet(Collection<Rule> rules) {
        Objects.requireNonNull(rules, "rules 不能为 null");
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("rules 不能为空");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Rule rule : rules) {
            Objects.requireNonNull(rule, "rules 不能包含 null 元素");
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("规则标识重复: " + rule.id());
            }
        }
        List<Rule> sorted = new ArrayList<>(rules);
        // 降序 + 稳定：List.sort 是稳定排序，只在 salience 严格不等时换位
        sorted.sort((left, right) -> Integer.compare(right.salience(), left.salience()));
        this.rules = Collections.unmodifiableList(sorted);
    }

    /**
     * 用规则数组构造。
     *
     * @param rules 规则
     * @return 规则集
     * @throws IllegalArgumentException 规则为空或存在重复标识时
     */
    public static RuleSet of(Rule... rules) {
        return new RuleSet(List.of(rules));
    }

    /**
     * 按优先级排好序的规则。
     *
     * @return 不可变规则列表
     */
    public List<Rule> rules() {
        return rules;
    }

    /**
     * 规则数量。
     *
     * @return 规则数量
     */
    public int size() {
        return rules.size();
    }

    /**
     * 按标识取规则。
     *
     * <p>{@code to} 指向下一条规则时用的就是标识。刻意<b>不</b>再提供按
     * {@code name} 查找的方法：标识才是引用键，显示名可能重复也可能被覆盖，
     * 提供两条查找路径只会诱导调用方用错那一个。</p>
     *
     * @param id 规则标识
     * @return 对应规则；不存在时返回 null
     */
    public Rule byId(String id) {
        Objects.requireNonNull(id, "id 不能为 null");
        for (Rule rule : rules) {
            if (rule.id().equals(id)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 规则标识到规则的索引，只读。
     *
     * @return 不可变映射
     */
    public Map<String, Rule> byIdIndex() {
        Map<String, Rule> index = new LinkedHashMap<>();
        for (Rule rule : rules) {
            index.put(rule.id(), rule);
        }
        return Collections.unmodifiableMap(index);
    }

    /**
     * 声明了下一条规则的规则标识。
     *
     * @return 不可变规则标识列表
     */
    public List<String> chainedIds() {
        List<String> ids = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.target() != null) {
                ids.add(rule.id());
            }
        }
        return Collections.unmodifiableList(ids);
    }

    @Override
    public String toString() {
        return "RuleSet" + rules;
    }
}
