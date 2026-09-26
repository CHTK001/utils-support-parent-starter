package com.chua.common.support.rule.decision;

import com.chua.common.support.rule.RuleException;

import java.util.List;

/**
 * 决策表定义（未编译）。
 *
 * <p>这是规则文件里的原始形态：每行的 {@code when} 还是表达式字符串。
 * 装配期由 {@code RuleAssembler} 用与规则表达式相同的编译器编译成
 * {@link DecisionTable}，因此决策行的类型检查、白名单、字面量加宽规则
 * 与规则里的 {@code expr} 完全一致。</p>
 *
 * @param id           决策标识
 * @param kind         决策种类，供实现分派
 * @param rows         候选行
 * @param defaultValue 全部不成立时的结果
 * @param hitPolicy    命中策略，取值见 {@link DecisionTable.HitPolicy}
 * @author CH
 * @since 4.0.0.43
 */
public record DecisionTableSpec(String id, String kind, List<Row> rows,
                                Object defaultValue, DecisionTable.HitPolicy hitPolicy) {

    /**
     * 未编译的候选行。
     *
     * @param priority 优先级，数值小者先求值
     * @param when     成立条件（表达式文本）
     * @param outcome  命中后的决策结果
     */
    public record Row(int priority, String when, Object outcome) {
    }

    /**
     * 紧凑构造器：校验并冻结。
     *
     * @throws RuleException id 为空或候选行为空时抛出
     */
    public DecisionTableSpec {
        if (id == null || id.isBlank()) {
            throw new RuleException("决策表缺少 id");
        }
        if (rows == null || rows.isEmpty()) {
            throw new RuleException("决策表[" + id + "] 没有任何候选行");
        }
        hitPolicy = hitPolicy == null ? DecisionTable.HitPolicy.FIRST : hitPolicy;
        kind = kind == null || kind.isBlank() ? "table" : kind;
        rows = List.copyOf(rows);
    }
}
