package com.chua.flow.support.node;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.flow.FlowInstance;
import com.chua.common.support.task.flow.FlowNode;
import com.chua.common.support.task.flow.FlowNodeExecutor;

/**
 * 终止节点执行器。
 *
 * <p>流程的出口节点，执行后立即终止当前流程，
 * 后续节点不再执行，实例进入完成状态。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("end")
@FlowNode(value = "end", describe = "终止节点")
public class EndFlowNode implements FlowNodeExecutor {

    /**
     * 执行终止节点。
     *
     * <p>调用 {@link FlowInstance#exit()} 触发引擎终止当前流程执行。</p>
     *
     * @param instance 当前流程实例
     */
    @Override
    public void execute(FlowInstance instance) {
        instance.exit();
    }
}
