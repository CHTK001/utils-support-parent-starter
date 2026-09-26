package com.chua.common.support.rule;

/**
 * 规则动作（RHS）。
 *
 * <p>对应 Drools 中 {@code then} 与 {@code end} 之间的结论部分。
 * 动作在规则激活后执行，可读取 {@link RuleContext} 中的事实绑定与全局变量，
 * 并通过上下文对工作内存做变更（新增 / 修改 / 撤销事实）。</p>
 *
 * <h3>执行语义</h3>
 * <ul>
 *   <li>动作按 {@link Rule} 中声明顺序依次执行</li>
 *   <li>动作抛出的 {@link RuntimeException} 会被引擎捕获并记入
 *       {@link RuleSession#failedCount()}，不会中断整轮推理（与 Drools 一致）</li>
 *   <li>动作内对工作内存的变更，会在下一轮议程评估中重新参与匹配</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@FunctionalInterface
public interface Action {

    /**
     * 执行动作。
     *
     * @param context 规则上下文
     */
    void execute(RuleContext context);
}
