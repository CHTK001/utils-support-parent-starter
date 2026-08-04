package com.chua.flow.support.node;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.flow.FlowInstance;
import com.chua.common.support.task.flow.FlowNode;
import com.chua.common.support.task.flow.FlowNodeExecutor;

/**
 * 起始节点执行器。
 *
 * <p>流程的入口节点，本身不执行任何业务逻辑，
 * 仅标记流程起始位置，引擎据此决定执行起点。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("start")
@FlowNode(value = "start", describe = "起始节点")
public class StartFlowNode implements FlowNodeExecutor {

    /**
     * 执行起始节点。
     *
     * <p>起始节点为空操作，后续节点由引擎按连线调度。</p>
     *
     * @param instance 当前流程实例
     */
    @Override
    public void execute(FlowInstance instance) {
    }
}
