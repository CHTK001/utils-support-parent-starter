package com.chua.common.support.task.pipeline.core;

import org.jspecify.annotations.NullUnmarked;

/**
 * 流水线节点接口。
 *
 * <p>所有节点类型的统一抽象。这是一个函数式接口，可通过 Lambda 表达式实现自定义节点。</p>
 *
 * <p>内置节点实现：</p>
 * <ul>
 *   <li>{@link com.chua.common.support.task.pipeline.node.TaskNode} — 执行节点</li>
 *   <li>{@link com.chua.common.support.task.pipeline.node.DecisionNode} — 判断节点</li>
 *   <li>{@link com.chua.common.support.task.pipeline.node.StartNode} — 起始节点</li>
 *   <li>{@link com.chua.common.support.task.pipeline.node.EndNode} — 终止节点</li>
 *   <li>{@link com.chua.common.support.task.pipeline.node.SubPipelineNode} — 子流水线节点</li>
 * </ul>
 *
 * @author CH
 */
@NullUnmarked
@FunctionalInterface
public interface PipelineNode {

    /**
     * 执行节点逻辑。
     *
     * <p>节点在此方法中实现具体业务逻辑，可通过 {@link PipelineContext}
     * 读取/修改数据、控制执行流程。</p>
     *
     * @param context 流水线上下文
     */
    void execute(PipelineContext<?> context);
}
