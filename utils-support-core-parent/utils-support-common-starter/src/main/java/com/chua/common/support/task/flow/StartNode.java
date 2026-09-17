package com.chua.common.support.task.flow;

/**
 * 起始节点接口。
 *
 * <p>流程的入口节点，本身不执行任何业务逻辑，
 * 仅标记流程起始位置，引擎据此决定执行起点。</p>
 *
 * <p>通过 {@link FlowGraph#start(String)} 指定起始节点时，
 * 节点需实现本接口或已通过 {@link Flow#addNode(String, FlowNode)} 加入流程。</p>
 *
 * @author CH
 * @since 4.0.0.42
*/
public interface StartNode extends FlowNode {

    @Override
    default String type() {
        return "start";
    }
}
