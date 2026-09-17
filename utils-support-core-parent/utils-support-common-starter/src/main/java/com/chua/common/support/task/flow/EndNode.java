package com.chua.common.support.task.flow;

/**
 * 终止节点接口。
 *
 * <p>流程的出口节点，执行后立即终止当前流程，
 * 后续节点不再执行，实例进入完成状态。</p>
 *
 * <p>通过 {@link FlowGraph#end(String...)} 指定终止节点时，
 * 节点需实现本接口或已通过 {@link Flow#addNode(String, FlowNode)} 加入流程。</p>
 *
 * @author CH
 * @since 4.0.0.42
*/
public interface EndNode extends FlowNode {

    @Override
    default String type() {
        return "end";
    }
}
