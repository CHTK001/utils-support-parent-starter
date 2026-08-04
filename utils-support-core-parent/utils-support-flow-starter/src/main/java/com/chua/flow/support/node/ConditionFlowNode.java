package com.chua.flow.support.node;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.flow.FlowInstance;
import com.chua.common.support.task.flow.FlowNode;
import com.chua.common.support.task.flow.FlowNodeExecutor;

/**
 * 条件节点执行器。
 *
 * <p>条件分支的声明节点，实际分支判断由引擎构建为
 * {@link com.chua.common.support.task.pipeline.node.DecisionNode} 处理，
 * 本执行器仅用于节点类型注册与前端类型清单展示。</p>
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
@Spi("condition")
@FlowNode(value = "condition", describe = "条件分支")
public class ConditionFlowNode implements FlowNodeExecutor {

    /**
     * 执行条件节点。
     *
     * <p>条件判断由引擎的 DecisionNode 完成，此处为空操作。</p>
     *
     * @param instance 当前流程实例
     */
    @Override
    public void execute(FlowInstance instance) {
    }
}
