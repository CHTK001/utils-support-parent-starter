package com.chua.common.support.task.pipeline.node;

import com.chua.common.support.task.pipeline.core.Action;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;

/**
 * 终止节点。
 *
 * <p>流水线的终止节点，执行时会将动作设为 {@link Action#EXIT} 结束流水线。
 * 可通过 {@link com.chua.common.support.task.pipeline.builder.PipelineBuilder#end(String)} 指定。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class EndNode implements PipelineNode {

    /**
     * 节点唯一标识
     */
    private final String id;

    /**
     * 构造终止节点。
     *
     * @param id 节点唯一标识
     */
    public EndNode(String id) {
        this.id = id;
    }

    /**
     * 获取节点 ID。
     *
     * @return 节点 ID
     */
    public String getId() {
        return id;
    }

    @Override
    public String getType() {
        return "end";
    }

    @Override
    public String execute(PipelineContext<?> context) {
        context.setCurrentNodeId(id);
        context.setAction(Action.EXIT);
        return null;
    }
}
