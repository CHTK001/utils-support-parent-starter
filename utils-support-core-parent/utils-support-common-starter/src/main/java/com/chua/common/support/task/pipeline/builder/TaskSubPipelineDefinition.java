package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.node.SubPipelineNode;

/**
 * 子流水线定义 — 类型安全的子流水线配置构建器。
 *
 * <p>由 {@link TaskDefinition#subPipeline(Pipeline)} 创建，
 * 支持链式配置后通过 {@link #taskEnd()} 完成定义并返回 {@link PipelineBuilder}。</p>
 *
 * <p><strong>与 TaskDefinition 的区别：</strong></p>
 * <ul>
 *   <li>绑定子流水线实例，执行时嵌套运行子流程</li>
 *   <li>可通过 {@link #withoutPipeline()} 退回 {@link TaskDefinition}</li>
 * </ul>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * Pipeline subPipeline = PipelineBuilder.newBuilder("sub")
 *     .task("subA", ctx -> { return null; }).taskEnd()
 *     .task("subB", ctx -> { return null; }).taskEnd()
 *     .build();
 *
 * Pipeline mainPipeline = PipelineBuilder.newBuilder("main")
 *     .task("mainStart", ctx -> { return null; }).taskEnd()
 *     .task("subStep", ctx -> { return null; })
 *     .subPipeline(subPipeline)             // → TaskSubPipelineDefinition
 *     .taskEnd()
 *     .task("mainEnd", ctx -> { return null; }).taskEnd()
 *     .build();
 * }</pre>
 *
 * @author CH
 * @see TaskDefinition
 */
public class TaskSubPipelineDefinition {

    private final String id;
    private final PipelineBuilder builder;
    private final Pipeline subPipeline;

    /**
     * 构造子流水线定义。
     *
     * @param id          节点唯一标识
     * @param builder     流水线构建器
     * @param subPipeline 子流水线实例
     */
    TaskSubPipelineDefinition(String id, PipelineBuilder builder, Pipeline subPipeline) {
        this.id = id;
        this.builder = builder;
        this.subPipeline = subPipeline;
    }

    /**
     * 完成定义，将子流水线节点添加到流水线，返回构建器继续链式配置。
     *
     * @return PipelineBuilder
     */
    public PipelineBuilder taskEnd() {
        SubPipelineNode node = new SubPipelineNode(id, subPipeline);
        builder.addNodeInternal(node);
        return builder;
    }

    /**
     * 退回任务节点定义。
     *
     * <p>放弃子流水线配置，返回 {@link TaskDefinition} 继续配置。
     * 注意：退回后 handler 为空实现（返回 null），需自行设置业务逻辑。</p>
     *
     * @return TaskDefinition（handler 为空实现）
     */
    public TaskDefinition withoutPipeline() {
        return new TaskDefinition(id, ctx -> null, builder);
    }
}