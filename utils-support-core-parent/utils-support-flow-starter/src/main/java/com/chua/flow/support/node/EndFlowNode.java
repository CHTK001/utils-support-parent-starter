package com.chua.flow.support.node;

import com.chua.common.support.task.flow.FlowContext;
import com.chua.common.support.task.flow.EndNode;

/**
 * 终止节点。
 *
 * <p>流程的出口节点，执行后立即终止当前流程，
 * 后续节点不再执行，实例进入完成状态。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class EndFlowNode implements EndNode {

    /**
     * 执行终止节点。
     *
     * <p>调用 {@link FlowContext#exit()} 触发引擎终止当前流程执行。</p>
     *
     * @param context 当前流程上下文
     */
    @Override
    public void execute(FlowContext context) {
        context.exit();
    }
}
