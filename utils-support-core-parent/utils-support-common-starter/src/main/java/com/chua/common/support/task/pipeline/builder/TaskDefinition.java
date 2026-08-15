package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.core.Action;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.node.TaskNode;

import java.util.Map;

/**
 * 任务节点定义 — 类型安全的流水线节点构建器。
 *
 * <p>由 {@link PipelineBuilder#task(String, PipelineNode)} 创建，
 * 支持链式配置后通过 {@link #taskEnd()} 完成定义并返回 {@link PipelineBuilder}。</p>
 *
 * <p><strong>核心设计：</strong></p>
 * <ul>
 *   <li>所有节点统一通过 {@code task()} 创建，避免类型混乱</li>
 *   <li>通过类型转换方法（{@link #decision()}、{@link #subPipeline(Pipeline)}）切换到专属定义</li>
 *   <li>每种定义有专属便捷方法，防止用户写错</li>
 * </ul>
 *
 * <p><strong>类型转换：</strong></p>
 * <ul>
 *   <li>{@link #decision()} → {@link TaskDecisionDefinition}（判断节点定义，支持分支配置）</li>
 *   <li>{@link #subPipeline(Pipeline)} → {@link TaskSubPipelineDefinition}（子流水线定义）</li>
 * </ul>
 *
 * <p><strong>便捷方法：</strong></p>
 * <ul>
 *   <li>{@link #end()} — 执行后自动终止流水线（设置 action=EXIT）</li>
 *   <li>{@link #start()} — 标记为起始节点</li>
 * </ul>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * // 基本任务
 * PipelineBuilder.newBuilder("flow")
 *     .task("step1", ctx -> { doWork(ctx); return null; })
 *     .taskEnd()
 *     .build();
 *
 * // 带便捷方法
 * PipelineBuilder.newBuilder("flow")
 *     .task("init", ctx -> { init(ctx); return null; })
 *     .start()       // 标记为起始节点
 *     .taskEnd()
 *     .task("done", ctx -> { cleanup(ctx); return null; })
 *     .end()         // 执行后终止流水线
 *     .taskEnd()
 *     .build();
 *
 * // 转为判断节点
 * PipelineBuilder.newBuilder("flow")
 *     .task("check", ctx -> condition ? "yes" : "no")
 *     .decision()    // → TaskDecisionDefinition
 *     .branch("yes", "processNode")
 *     .branch("no", "errorNode")
 *     .taskEnd()
 *     .build();
 * }</pre>
 *
 * @author CH
 * @see TaskDecisionDefinition
 * @see TaskSubPipelineDefinition
 */
public class TaskDefinition {

    private final String id;
    private PipelineNode handler;
    private final PipelineBuilder builder;
    private boolean endAfterExecute;
    private boolean startNode;

    /**
     * 构造任务定义。
     *
     * @param id      节点唯一标识
     * @param handler 业务逻辑处理器
     * @param builder 流水线构建器
     */
    TaskDefinition(String id, PipelineNode handler, PipelineBuilder builder) {
        this.id = id;
        this.handler = handler;
        this.builder = builder;
    }

    /**
     * 完成定义，将任务节点添加到流水线，返回构建器继续链式配置。
     *
     * @return PipelineBuilder
     */
    public PipelineBuilder taskEnd() {
        PipelineNode effectiveHandler = endAfterExecute ? wrapWithEnd(handler) : handler;
        TaskNode node = new TaskNode(id, effectiveHandler);
        builder.addNodeInternal(node);
        if (startNode) {
            builder.start(id);
        }
        return builder;
    }

    /**
     * 转为判断节点定义。
     *
     * <p>当前任务的 handler 成为判断节点的路由器（返回目标节点 ID），
     * 可通过 {@link TaskDecisionDefinition#branch(String, String)} 配置分支映射。</p>
     *
     * <p>用法示例：</p>
     * <pre>{@code
     * .task("check", ctx -> condition ? "yes" : "no")
     * .decision()                        // → TaskDecisionDefinition
     * .branch("yes", "processNode")      // 添加分支
     * .branch("no", "errorNode")
     * .taskEnd()                         // 完成定义
     * }</pre>
     *
     * @return TaskDecisionDefinition
     */
    public TaskDecisionDefinition decision() {
        return new TaskDecisionDefinition(id, handler, builder);
    }

    /**
     * 转为子流水线定义。
     *
     * <p>当前任务的 handler 被替换为子流水线执行逻辑。</p>
     *
     * @param subPipeline 子流水线实例
     * @return TaskSubPipelineDefinition
     */
    public TaskSubPipelineDefinition subPipeline(Pipeline subPipeline) {
        return new TaskSubPipelineDefinition(id, builder, subPipeline);
    }

    /**
     * 便捷方法：执行后自动终止流水线。
     *
     * <p>包装 handler，在执行完毕后设置 {@code ctx.setAction(Action.EXIT)}，
     * 无论 handler 返回什么值，流水线都将终止。</p>
     *
     * <p>等价于：</p>
     * <pre>{@code
     * .task("done", ctx -> {
     *     cleanup(ctx);
     *     ctx.setAction(Action.EXIT);
     *     return null;
     * })
     * }</pre>
     *
     * @return this
     */
    public TaskDefinition end() {
        this.endAfterExecute = true;
        return this;
    }

    /**
     * 便捷方法：标记当前节点为起始节点。
     *
     * <p>等价于在 PipelineBuilder 上调用 {@code .start(id)}。</p>
     *
     * @return this
     */
    public TaskDefinition start() {
        this.startNode = true;
        return this;
    }

    /**
     * 设置节点参数（JSON 构建时传入，执行时注入到 ctx.nodeLocalData）。
     *
     * @param params 节点参数映射
     * @return this
     */
    public TaskDefinition params(Map<String, Object> params) {
        // 参数将在 taskEnd() 时设置到 TaskNode
        // 暂存到 handler 包装中，taskEnd 时应用
        PipelineNode original = this.handler;
        this.handler = new PipelineNode() {
            @Override
            public String execute(com.chua.common.support.task.pipeline.core.PipelineContext<?> context) {
                return original.execute(context);
            }

            @Override
            public Map<String, Object> getParams() {
                return params;
            }
        };
        return this;
    }

    /**
     * 包装 handler：执行后设置 EXIT 动作。
     */
    private static PipelineNode wrapWithEnd(PipelineNode original) {
        return ctx -> {
            String result = original.execute(ctx);
            ctx.setAction(Action.EXIT);
            return null;
        };
    }
}