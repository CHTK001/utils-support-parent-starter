package com.chua.common.support.ai.rule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次规则执行的结果。
 *
 * <p>刻意做成不可变快照：{@link #vars()} 把本次执行结束时上下文的全部五层
 * （规则声明、规则中间结果、决策展平、原始输入与环境量）拍平成一份冻结的
 * {@link Map}。执行结束后上下文不应再被改动，否则「这次判定依据什么」
 * 就无从追溯了——尤其规则里常有「先命中再改写同一变量」的写法，
 * 不冻结就看不出调用方拿到的结论是改写前还是改写后的。</p>
 *
 * <p><b>刻意不记录「每个变量最终来自哪一层」</b>。半份溯源答不上真正的问题：
 * 调试时想知道的是「被盖掉的那层原本是多少」，而那需要保留全部层的值，
 * 等于把整个上下文原样暴露出去，与本类冻结快照的设计直接矛盾，
 * 还会多出一张可能与 {@link #vars()} 失同步的映射表。
 * 替代路径已经够用且零成本：{@link RuleSet#byIdIndex()} 里有各规则的
 * {@code var} 声明，{@link #fired()} 给出执行顺序，
 * 再加上 {@link #vars()} 的拍平结果，三者一 diff 即可还原谁盖了谁。</p>
 *
 * @param effect 聚合后的结果语义
 * @param fired  实际命中并执行了动作的规则名，按执行顺序
 * @param matched 条件成立但没有动作的规则名
 * @param target  实际跟随到的下一条规则标识，可为 null
 * @param halted 是否因命中 {@link RuleEffect#HALT} 提前中断
 * @param vars   冻结后的全部变量，含规则声明、决策展平、规则中间结果
 * @author CH
 * @since 4.0.0.42
 */
public record RuleResult(RuleEffect effect,
                         List<String> fired,
                         List<String> matched,
                         String target,
                         boolean halted,
                         Map<String, Object> vars) {

    /**
     * 构造执行结果。
     *
     * @throws NullPointerException 列表或映射为 null 时
     */
    public RuleResult {
        Objects.requireNonNull(effect, "effect 不能为 null");
        fired = List.copyOf(Objects.requireNonNull(fired, "fired 不能为 null"));
        matched = List.copyOf(Objects.requireNonNull(matched, "matched 不能为 null"));
        vars = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(vars, "vars 不能为 null")));
    }

    /**
     * 命中的规则名（无论有无动作），按执行顺序。
     *
     * @return 不可变规则名列表
     */
    public List<String> hitNames() {
        List<String> all = new ArrayList<>(fired);
        for (String name : matched) {
            if (!all.contains(name)) {
                all.add(name);
            }
        }
        return Collections.unmodifiableList(all);
    }

    /**
     * 读了某个变量。
     *
     * @param name 变量名
     * @return 变量值；不存在或值为 null 时返回 {@link Optional#empty()}
     */
    public Optional<Object> value(String name) {
        return Optional.ofNullable(vars.get(name));
    }

    /**
     * 有规则命中。
     *
     * @return 至少命中一条返回 true
     */
    public boolean anyHit() {
        return !fired.isEmpty() || !matched.isEmpty();
    }

    /**
     * 汇总多个执行结果，取更重的效果档位。
     *
     * @param results 执行结果集合，可为 null
     * @return 最重的效果；无结果时返回 {@link RuleEffect#NONE}
     */
    public static RuleEffect heaviest(Collection<RuleResult> results) {
        RuleEffect effect = RuleEffect.NONE;
        if (results != null) {
            for (RuleResult result : results) {
                effect = RuleEffect.heavier(effect, result.effect());
            }
        }
        return effect;
    }
}
