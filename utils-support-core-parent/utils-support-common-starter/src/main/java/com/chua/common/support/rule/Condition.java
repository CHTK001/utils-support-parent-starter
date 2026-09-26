package com.chua.common.support.rule;

/**
 * 规则条件（LHS）。
 *
 * <p>对应 Drools 中 {@code when} 与 {@code then} 之间的条件部分。
 * 条件是一个可组合的布尔结构，支持 AND / OR / NOT 短路求值，
 * 并可通过 {@link Pattern} 从工作内存中匹配事实并绑定变量。</p>
 *
 * <h3>两种条件形态</h3>
 * <ul>
 *   <li><b>纯谓词条件</b> — 只依赖 {@link RuleContext} 中已有数据求值，
 *       例如 {@code Conditions.gt(ctx -> ctx.get("amount"), 1000)}</li>
 *   <li><b>事实模式</b> — 由 {@link Pattern} 实现，从工作内存匹配事实，
 *       并把命中的事实绑定到上下文，供 RHS 使用</li>
 * </ul>
 *
 * <h3>元组激活语义</h3>
 * <p>当规则的 {@code when} 是由多个 {@link Pattern} 组成的
 * {@link Conditions#and(Condition...) and 条件} 时，引擎会对所有
 * {@link Pattern} 做笛卡尔积，每个不同的绑定组合产生一次独立的规则激活。
 * 这与 Drools 的 tuple 语义一致，避免了「一条规则只能触发一次」的局限。</p>
 *
 * <h3>短路规则</h3>
 * <ul>
 *   <li>AND：任一子条件为 false 时立即返回 false，后续子条件不再求值</li>
 *   <li>OR：任一子条件为 true 时立即返回 true，后续子条件不再求值</li>
 *   <li>NOT：先求值子条件再取反</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@FunctionalInterface
public interface Condition {

    /**
     * 求值当前条件是否成立。
     *
     * @param context 规则上下文，提供事实绑定与全局变量
     * @return 条件成立返回 true
     */
    boolean test(RuleContext context);

    /**
     * 构造 AND 条件。
     *
     * @param other 另一个条件
     * @return 组合后的 AND 条件
     */
    default Condition and(Condition other) {
        return Conditions.and(this, other);
    }

    /**
     * 构造 OR 条件。
     *
     * @param other 另一个条件
     * @return 组合后的 OR 条件
     */
    default Condition or(Condition other) {
        return Conditions.or(this, other);
    }

    /**
     * 构造 NOT 条件。
     *
     * @return 取反后的条件
     */
    default Condition negate() {
        return Conditions.not(this);
    }
}
