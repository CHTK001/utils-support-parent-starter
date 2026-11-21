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
public record StartNode(String id, String nextNodeId) implements PipelineNode {

    /**
     * 节点唯一标识
     */
    public String id() {
        return id;
    }

    /**
     * 起始节点的下一节点 标识
     */
    public String nextNodeId() {
        return nextNodeId;
    }

    /**
     * 构造起始节点。
     *
     * @param id         节点唯一标识
     * @param nextNodeId 下一节点 标识
     */
    public StartNode {
    }

    /**
     * 获取节点 标识。
     *
     * @return 节点 标识
     */
    public String getId() {
        return id();
    }

    /**
     * 节点类型：启动。
     */
    @Override
    public String getType() {
        return "start";
    }

    /**
     * 进入起始节点：设置当前节点并跳转到下一节点。
     */
    @Override
    public String execute(PipelineContext<?> context) {
        context.setCurrentNodeId(id());
        return nextNodeId();
    }
}
