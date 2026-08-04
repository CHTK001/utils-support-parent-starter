package com.chua.common.support.task.flow;

/**
 * 条件节点接口。
 *
 * <p>流程中的分支决策节点，通过 {@link #test(FlowContext)} 求值判断结果，
 * 引擎按结果走注册的 true/false 分支。与普通节点的区别在于：
 * 条件节点执行时不会直接进入默认顺序边，而是根据判断结果选择分支走向。</p>
 *
 * <p>在 {@link FlowGraph} 中通过 {@code when(nodeId, result, target)}
 * 配置两个分支的目标节点。</p>
 *
 * <p>节点属性说明：</p>
 * <ul>
 *   <li>{@code key} — 取值键，为 "current" 时取当前数据，否则取上下文属性</li>
 *   <li>{@code equals} — 等于比较的期望值（可选）</li>
 *   <li>{@code notEmpty} — 是否要求非空（可选，默认 true）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ConditionNode extends FlowNode {

    @Override
    default String type() {
        return "condition";
    }

    /**
     * 求值条件判断结果。
     *
     * <p>引擎在调度到该节点时调用，返回 true 走 true 分支，
     * 返回 false 走 false 分支。</p>
     *
     * @param context 当前流程上下文
     * @return 条件判断结果
     */
    boolean test(FlowContext context);

    @Override
    default void execute(FlowContext context) {
        // 条件节点不做默认执行，判断由引擎按 test 结果调度
    }
}
