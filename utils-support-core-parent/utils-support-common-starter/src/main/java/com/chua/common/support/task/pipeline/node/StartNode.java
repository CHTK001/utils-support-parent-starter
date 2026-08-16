package com.chua.common.support.task.pipeline.node;

import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;

/**
 * 起始节点。
 *
 * <p>流水线的入口节点，用于指定流水线的起始位置。
 * 一般情况下无需手动创建，{@link com.chua.common.support.task.pipeline.builder.PipelineBuilder}
 * 会自动将添加的第一个节点设为起始节点。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class StartNode implements PipelineNode {

    /**
     * 节点唯一标识
     */
    private final String id;

    /**
     * 起始节点的下一节点 ID
     */
    private final String nextNodeId;

    /**
     * 构造起始节点。
     *
     * @param id         节点唯一标识
     * @param nextNodeId 下一节点 ID
     */
    public StartNode(String id, String nextNodeId) {
        this.id = id;
        this.nextNodeId = nextNodeId;
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
        return "start";
    }

    @Override
    public String execute(PipelineContext<?> context) {
        context.setCurrentNodeId(id);
        return nextNodeId;
    }
}
