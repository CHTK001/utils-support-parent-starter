package com.chua.flow.support.node;

import com.chua.common.support.task.flow.FlowContext;
import com.chua.common.support.task.flow.StartNode;

/**
* 起始节点。
*
* <p>流程的入口节点，本身不执行任何业务逻辑，
* 仅标记流程起始位置，引擎据此决定执行起点。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class StartFlowNode implements StartNode {

    /**
    * 执行起始节点。
    *
    * <p>起始节点为空操作，后续节点由引擎按连线调度。</p>
    *
    * @param context 当前流程上下文
     */
    @Override
    public void execute(FlowContext context) {
    }
}
