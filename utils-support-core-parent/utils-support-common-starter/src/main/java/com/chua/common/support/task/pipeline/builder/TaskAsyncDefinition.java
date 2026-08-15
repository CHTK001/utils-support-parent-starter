package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.core.Action;
import com.chua.common.support.task.pipeline.core.AsyncResult;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.node.AsyncSubPipelineNode;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 异步子流水线节点定义 — 类型安全的异步子流水线配置构建器。
 *
 * <p>通过 {@link TaskDefinition#async(Pipeline)} 从任务定义转换而来，
 * 通过 {@link #taskEnd()} 完成定义并返回 {@link PipelineBuilder}。</p>
 *
 * <p><strong>与 TaskSubPipelineDefinition 的核心区别：</strong></p>
 * <ul>
 *   <li>TaskSubPipelineDefinition — 同步执行，主干阻塞等待子流程完成</li>
 *   <li>TaskAsyncDefinition — 异步执行，主干不等待，子流程在后台线程执行</li>
 * </ul>
 *
 * <p><strong>完整模式：task → async → ... → taskEnd</strong></p>
 * <pre>{@code
 * Pipeline asyncSub = PipelineBuilder.newBuilder("asyncSub")
 *     .task("a1", ctx -> { ...; return null; }).taskEnd()
 *     .task("a2", ctx -> { ...; return null; }).taskEnd()
 *     .build();
 *
 * PipelineBuilder.newBuilder("mainFlow")
 *     .task("asyncStep", ctx -> null)
 *     .async(asyncSub)                  // 转为异步子流水线定义
 *     .mergeCurrentData(true)           // 完成后回写 currentData
 *     .onComplete((ctx, result) -> {    // 完成回调
 *         log.info("Async completed: {}", result.getOutput());
 *     })
 *     .taskEnd()                        // 结束定义
 *     .build();
 * }</pre>
 *
 * <p><strong>便捷方法：</strong></p>
 * <ul>
 *   <li>{@link #onStep(Consumer)} — 无返回值的步骤（Consumer 模式）</li>
 *   <li>{@link #step(PipelineNode)} — 有返回值的步骤（Function 模式）</li>
 *   <li>{@link #ext()} — 执行后自动终止流水线（等价于 action=EXIT）</li>
 *   <li>{@link #end()} — 同 {@link #ext()}，执行后终止流水线</li>
 *   <li>{@link #start()} — 设置子流水线起始节点 ID</li>
 *   <li>{@link #params(Map)} — 设置子流水线参数</li>
 *   <li>{@link #mergeCurrentData(boolean)} — 完成后是否回写 currentData</li>
 *   <li>{@link #onComplete(BiConsumer)} — 异步完成回调</li>
 * </ul>
 *
 * @author CH
 * @see TaskDefinition#async(Pipeline)
 * @see AsyncSubPipelineNode
 * @see AsyncResult
 */
public class TaskAsyncDefinition {

    private final String id;
    private PipelineNode preHandler;
    private final PipelineBuilder builder;
    private final Pipeline subPipeline;
    private boolean endAfterExecute;
    private String startNode;
    private Map<String, Object> params;
    private boolean mergeCurrentData;
    private BiConsumer<PipelineContext<?>, AsyncResult> completionHandler;

    /**
     * 构造异步子流水线定义。
     *
     * @param id          节点唯一标识
     * @param builder     流水线构建器
     * @param subPipeline 异步子流水线实例
     */
    TaskAsyncDefinition(String id, PipelineBuilder builder, Pipeline subPipeline) {
        this.id = id;
        this.builder = builder;
        this.subPipeline = subPipeline;
    }

    /**
     * 完成定义，将异步子流水线节点添加到流水线，返回构建器继续链式配置。
     *
     * <p>与 {@link TaskDefinition#async(Pipeline)} 配对使用，
     * 构成完整的异步子流水线定义：task → async → ... → taskEnd。</p>
     *
     * @return PipelineBuilder
     */
    public PipelineBuilder taskEnd() {
        PipelineNode effectivePreHandler = endAfterExecute ? wrapWithEnd(preHandler) : preHandler;
        AsyncSubPipelineNode node = new AsyncSubPipelineNode(id, subPipeline);
        if (effectivePreHandler != null) {
            node.preHandler(effectivePreHandler);
        }
        if (startNode != null) {
            node.start(startNode);
        }
        if (params != null && !params.isEmpty()) {
            node.params(params);
        }
        node.mergeCurrentData(mergeCurrentData);
        if (completionHandler != null) {
            node.onComplete(completionHandler);
        }
        builder.addNodeInternal(node);
        return builder;
    }

    /**
     * 结束当前节点定义并完成整个流水线构建。
     *
     * <p>等价于 {@code .taskEnd().end(id).build()}，一步完成三件事：</p>
     * <ol>
     *   <li>调用 {@link #taskEnd()} 完成当前异步子流水线节点定义</li>
     *   <li>将当前节点标记为流水线终止节点</li>
     *   <li>构建并返回 {@link Pipeline} 实例</li>
     * </ol>
     *
     * @return 构建完成的 Pipeline 实例
     */
    public Pipeline pipelineEnd() {
        taskEnd();
        builder.end(id);
        return builder.build();
    }

    /**
     * 设置前置处理器（在异步子流水线启动前调用）。
     *
     * @param handler 前置处理器
     * @return this
     */
    public TaskAsyncDefinition preHandler(PipelineNode handler) {
        this.preHandler = handler;
        return this;
    }

    /**
     * 设置子流水线起始节点 ID。
     *
     * @param startNodeId 子流水线中的起始节点 ID
     * @return this
     */
    public TaskAsyncDefinition start(String startNodeId) {
        this.startNode = startNodeId;
        return this;
    }

    /**
     * 设置子流水线参数。
     *
     * @param params 参数映射
     * @return this
     */
    public TaskAsyncDefinition params(Map<String, Object> params) {
        this.params = params;
        return this;
    }

    /**
     * 设置异步完成后是否将输出合并到父上下文的 currentData。
     *
     * <p>默认 false。注意：异步完成时主干可能已在其他节点，
     * 合并 currentData 可能覆盖当前节点的数据。
     * 建议仅在确定安全时启用，或通过 {@link #onComplete(BiConsumer)} 手动合并。</p>
     *
     * @param mergeCurrentData true 表示异步完成后将输出写回父上下文的 currentData
     * @return this
     */
    public TaskAsyncDefinition mergeCurrentData(boolean mergeCurrentData) {
        this.mergeCurrentData = mergeCurrentData;
        return this;
    }

    /**
     * 设置异步完成回调。
     *
     * <p>异步子流水线执行完毕后触发，参数为父上下文和异步结果。
     * 可用于自定义结果合并逻辑、通知、日志等。</p>
     *
     * @param completionHandler 完成回调
     * @return this
     */
    public TaskAsyncDefinition onComplete(BiConsumer<PipelineContext<?>, AsyncResult> completionHandler) {
        this.completionHandler = completionHandler;
        return this;
    }

    /**
     * 设置无返回值的步骤处理器（Consumer 模式）。
     *
     * @param action Consumer 回调
     * @return this
     */
    public TaskAsyncDefinition onStep(Consumer<PipelineContext<?>> action) {
        PipelineNode original = this.preHandler;
        this.preHandler = ctx -> {
            if (original != null) {
                original.execute(ctx);
            }
            action.accept(ctx);
            return null;
        };
        return this;
    }

    /**
     * 设置有返回值的步骤处理器（Function 模式）。
     *
     * @param handler PipelineNode 处理器
     * @return this
     */
    public TaskAsyncDefinition step(PipelineNode handler) {
        this.preHandler = handler;
        return this;
    }

    /**
     * 便捷方法：执行后自动终止流水线（等价于 action=EXIT）。
     *
     * @return this
     */
    public TaskAsyncDefinition ext() {
        this.endAfterExecute = true;
        return this;
    }

    /**
     * 便捷方法：执行后自动终止流水线。
     *
     * <p>与 {@link #ext()} 完全等价。</p>
     *
     * @return this
     */
    public TaskAsyncDefinition end() {
        this.endAfterExecute = true;
        return this;
    }

    /**
     * 包装 handler：执行后设置 EXIT 动作。
     */
    private static PipelineNode wrapWithEnd(PipelineNode original) {
        if (original == null) {
            return ctx -> {
                ctx.setAction(Action.EXIT);
                return null;
            };
        }
        return ctx -> {
            String result = original.execute(ctx);
            ctx.setAction(Action.EXIT);
            return null;
        };
    }
}