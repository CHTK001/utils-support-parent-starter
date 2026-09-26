package com.chua.common.support.rule.decision;

import com.chua.common.support.rule.Condition;

import java.util.List;
import java.util.Objects;

/**
 * 决策表定义。
 *
 * <p>表里的每一行的条件在<b>装配期</b>就已编译成
 * {@link Condition}（与 {@code expr} 节点走同一套表达式编译器），
 * 因此运行期不再解析表达式，只做求值。</p>
 *
 * @param id           决策标识，规则文件用它引用本表
 * @param kind         决策种类，供 {@link RuleDecisionProvider#supports} 分派
 * @param rows         候选行，按 {@link Row#priority()} 升序求值
 * @param defaultValue 全部候选行都不成立时的结果，可为 null
 * @param hitPolicy    命中策略
 * @author CH
 * @since 4.0.0.43
 */
public record DecisionTable(String id, String kind, List<Row> rows,
                            Object defaultValue, HitPolicy hitPolicy) {

    /**
     * 决策表候选行。
     *
     * @param priority  优先级，数值小者先求值
     * @param condition 该行成立条件
     * @param outcome   命中后的决策结果
     */
    public record Row(int priority, Condition condition, Object outcome) {

        /**
         * 规范构造器：成立条件为空值敌对。
         *
         * <p>value class 前置条件——引用组件不接受 null。
         * 唯一构造点（{@code RuleAssembler}）传入的 condition 来自
         * {@code ExpressionCompiler#compile}，该实现要么抛异常要么返回非空条件。
         * {@code outcome} 是决策结果，允许为 null（与 defaultValue 同语义），不校验。</p>
         *
         * @param priority  优先级
         * @param condition 该行成立条件
         * @param outcome   命中后的决策结果，可为 null
         */
        public Row {
            condition = Objects.requireNonNull(condition, "condition 不能为 null");
        }
    }

    /**
     * 命中策略。
     */
    public enum HitPolicy {

        /**
         * 取第一个成立的候选行（决策表的默认语义）
         */
        FIRST,

        /**
         * 要求所有成立的候选行结果一致；出现冲突视为决策表配置错误
         */
        UNIQUE
    }

    /**
     * 紧凑构造器：做基础校验并冻结列表。
     *
     * @throws com.chua.common.support.rule.RuleException id 为空或候选行为空时抛出
     */
    public DecisionTable {
        if (id == null || id.isBlank()) {
            throw new com.chua.common.support.rule.RuleException("决策表缺少 id");
        }
        if (rows == null || rows.isEmpty()) {
            throw new com.chua.common.support.rule.RuleException(
                    "决策表[" + id + "] 没有任何候选行");
        }
        hitPolicy = hitPolicy == null ? HitPolicy.FIRST : hitPolicy;
        kind = kind == null || kind.isBlank() ? "table" : kind;
        rows = List.copyOf(rows);
    }
}
