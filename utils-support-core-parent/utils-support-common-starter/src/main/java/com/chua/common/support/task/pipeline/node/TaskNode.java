package com.chua.common.support.task.pipeline.node;

import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;

import java.util.function.Consumer;

/**
 * 执行节点。
 *
 * <p>最常用的节点类型，用于执行具体的业务逻辑。
 * 通过 {@link Consumer} 接收 {@link PipelineContext}，可读写当前数据、控制执行动作。</p>
 *
 * <p>用法示例：</p>
 * <pre>{@code
 * .task("validate", ctx -> {
 *     String data = ctx.getCurrentData();
 *     if (data == null) {
 *         ctx.setAction(Action.EXIT);
 *     }
 * })
 * }</pre>
 *
 * @author CH
 */
public class TaskNode implements PipelineNode {

    /**
     * 节点唯一标识
     */
    private final String id;

    /**
     * 业务逻辑执行器
     */
    private final Consumer<PipelineContext<?>> task;

    /**
     * 构造执行节点。
     *
     * @param id   节点唯一标识
     * @param task 业务逻辑执行器
     */
    public TaskNode(String id, Consumer<PipelineContext<?>> task) {
        this.id = id;
        this.task = task;
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
    public void execute(PipelineContext<?> context) {
        context.setCurrentNodeId(id);
        task.accept(context);
    }
}
