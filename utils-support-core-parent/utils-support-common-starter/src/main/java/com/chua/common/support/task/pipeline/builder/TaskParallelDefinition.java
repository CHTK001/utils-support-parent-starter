package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.core.AsyncResult;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.node.ParallelNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 并行子流水线节点定义 — 类型安全的并行子流水线配置构建器。
 *
 * <p>通过 {@link TaskDefinition#parallel(Pipeline)} 从任务定义转换而来，
 * 或通过 {@link TaskForkDefinition#parallel(Pipeline)} 从分叉定义转换而来，
 * 通过 {@link #taskEnd()} 完成定义并返回 {@link PipelineBuilder}。</p>
 *
 * <p><strong>与 TaskSubPipelineDefinition 的核心区别：</strong></p>
 * <ul>
 *   <li>TaskSubPipelineDefinition — 同步执行，主干阻塞等待子流程完成</li>
 *   <li>TaskParallelDefinition — 并行执行，主干不等待，子流程在后台线程执行</li>
 * </ul>
 *
 * <p><strong>与 TaskForkDefinition 的核心区别：</strong></p>
 * <ul>
 *   <li>TaskForkDefinition — 分叉+阻塞，多分支并行执行，主干等待所有分支完成</li>
 *   <li>TaskParallelDefinition — 并行+不阻塞，单个子流程在后台执行，主干继续</li>
 * </ul>
 *
 * <p><strong>完整模式：task → parallel → ... → taskEnd</strong></p>
 * <pre>{@code
 * Pipeline parallelSub = PipelineBuilder.newBuilder("parallelSub")
 *     .task("a1", ctx -> { ...; return null; }).taskEnd()
 *     .task("a2", ctx -> { ...; return null; }).taskEnd()
 *     .build();
 *
 * PipelineBuilder.newBuilder("mainFlow")
 *     .task("parallelStep", ctx -> null)
 *     .parallel(parallelSub)                  // 转为并行子流水线定义
 *     .onComplete((ctx, result) -> {          // 完成回调
 *         log.info("Parallel completed: {}", result.getOutput());
 *     })
 *     .taskEnd()                              // 结束定义
 *     .build();
 * }</pre>
 *
 * <p><strong>便捷方法：</strong></p>
 * <ul>
 *   <li>{@link #onStep(Consumer)} — 无返回值的步骤（Consumer 模式）</li>
 *   <li>{@link #step(PipelineNode)} — 有返回值的步骤（Function 模式）</li>
 *   <li>{@link #start(String)} — 设置子流水线起始节点 ID</li>
 *   <li>{@link #params(Map)} — 设置子流水线参数</li>
 *   <li>{@link #environment(Map)} — 设置节点自有变量</li>
 *   <li>{@link #mergeCurrentData(boolean)} — 完成后是否回写 currentData（默认 true）</li>
 *   <li>{@link #onComplete(BiConsumer)} — 并行完成回调</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 * @see TaskDefinition#parallel(Pipeline)
 * @see TaskForkDefinition#parallel(Pipeline)
 * @see ParallelNode
 * @see AsyncResult
 */
public class TaskParallelDefinition {

    /** ID */
    private final String id;
    /** PRE处理器 */
    private PipelineNode preHandler;
    /** 构建器 */
    private final PipelineBuilder builder;
    /** SUB管道 */
    private final Pipeline subPipeline;
    /** 开始节点 */
    private String startNode;
    private Map<String, Object> params;
    private Map<String, Object> env;
    private Map<String, Object> environment;
    /** Merge当前数据 */
    private boolean mergeCurrentData = true;
    private BiConsumer<PipelineContext<?>, AsyncResult> completionHandler;

    /**
     * 构造并行子流水线定义。
     *
     * @param id          节点唯一标识
     * @param builder     流水线构建器
     * @param subPipeline 并行子流水线实例
     */
    TaskParallelDefinition(String id, PipelineBuilder builder, Pipeline subPipeline) {
        this.id = id;
        this.builder = builder;
        this.subPipeline = subPipeline;
    }

    /**
     * 完成定义，将并行子流水线节点添加到流水线，返回构建器继续链式配置。
     *
     * <p>与 {@link TaskDefinition#parallel(Pipeline)} 配对使用，
     * 构成完整的并行子流水线定义：task → parallel → ... → taskEnd。</p>
     *
     * @return PipelineBuilder
     */
    public PipelineBuilder taskEnd() {
        ParallelNode node = new ParallelNode(id, subPipeline);
        if (preHandler != null) {
            node.preHandler(preHandler);
        }
        if (startNode != null) {
            node.start(startNode);
        }
        if (params != null && !params.isEmpty()) {
            node.params(params);
        }
        if (env != null && !env.isEmpty()) {
            node.setEnv(env);
        }
        // environment 注入到 params 中（与 TaskDefinition 的 environment 语义一致）
        if (environment != null && !environment.isEmpty()) {
            Map<String, Object> mergedParams = new LinkedHashMap<>();
            if (node.getParams() != null && !node.getParams().isEmpty()) {
                mergedParams.putAll(node.getParams());
            }
            mergedParams.putAll(environment);
            node.params(mergedParams);
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
     *   <li>调用 {@link #taskEnd()} 完成当前并行子流水线节点定义</li>
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
     * 设置前置处理器（在并行子流水线启动前调用）。
     *
     * @param handler 前置处理器
     * @return this
     */
    public TaskParallelDefinition preHandler(PipelineNode handler) {
        this.preHandler = handler;
        return this;
    }

    /**
     * 设置子流水线起始节点 ID。
     *
     * @param startNodeId 子流水线中的起始节点 ID
     * @return this
     */
    public TaskParallelDefinition start(String startNodeId) {
        this.startNode = startNodeId;
        return this;
    }

    /**
     * 设置子流水线参数。
     *
     * @param params 参数映射
     * @return this
     */
    public TaskParallelDefinition params(Map<String, Object> params) {
        this.params = params;
        return this;
    }

    /**
     * 设置节点环境参数（运行时环境配置，如模型路径、阈值等）。
     *
     * <p>环境参数与 {@link #params(Map)} 的区别：</p>
     * <ul>
     *   <li><strong>params</strong> — 静态参数，注入到子上下文的 nodeLocalData 根级</li>
     *   <li><strong>env</strong> — 运行时环境参数，注入到 nodeLocalData 时以 {@code "env."} 前缀隔离，
     *       通过 {@code ctx.getNodeLocalValue("env.modelPath")} 获取</li>
     * </ul>
     *
     * @param env 环境参数映射
     * @return this
     */
    public TaskParallelDefinition env(Map<String, Object> env) {
        this.env = env;
        return this;
    }

    /**
     * 设置节点环境参数（单个键值对）。
     *
     * <p>等价于先创建 Map 再调用 {@link #env(Map)}，适用于少量参数的场景。</p>
     *
     * @param key   参数键
     * @param value 参数值
     * @return this
     */
    public TaskParallelDefinition env(String key, Object value) {
        if (this.env == null) {
            this.env = new LinkedHashMap<>();
        }
        this.env.put(key, value);
        return this;
    }

    /**
     * 设置节点自有变量（节点级别的局部变量存储）。
     *
     * <p>environment 与 params/env 的区别：</p>
     * <ul>
     *   <li><strong>params</strong> — 静态参数，注入到 nodeLocalData 根级</li>
     *   <li><strong>env</strong> — 运行时环境参数，以 {@code "env."} 前缀隔离</li>
     *   <li><strong>environment</strong> — 节点自有变量，直接注入到 nodeLocalData 根级，
     *       用于存储节点运行时产生的中间状态、计算结果等</li>
     * </ul>
     *
     * @param environment 节点自有变量映射
     * @return this
     */
    public TaskParallelDefinition environment(Map<String, Object> environment) {
        this.environment = environment;
        return this;
    }

    /**
     * 设置节点自有变量（单个键值对）。
     *
     * <p>等价于先创建 Map 再调用 {@link #environment(Map)}，适用于少量变量的场景。</p>
     *
     * @param key   变量键
     * @param value 变量值
     * @return this
     */
    public TaskParallelDefinition environment(String key, Object value) {
        if (this.environment == null) {
            this.environment = new LinkedHashMap<>();
        }
        this.environment.put(key, value);
        return this;
    }

    /**
     * 设置并行完成后是否将输出合并到父上下文的 currentData。
     *
     * <p>默认 true。并行子流程的结果必须合并回主干，否则后续节点无法获取并行执行的结果。
     * 默认启用合并，确保数据流完整性。</p>
     *
     * <p>注意：并行完成时主干可能已在其他节点，合并 currentData 可能覆盖当前节点的数据。
     * 如需自定义合并逻辑，可通过 {@link #onComplete(BiConsumer)} 手动处理。</p>
     *
     * @param mergeCurrentData true 表示并行完成后将输出写回父上下文的 currentData
     * @return this
     */
    public TaskParallelDefinition mergeCurrentData(boolean mergeCurrentData) {
        this.mergeCurrentData = mergeCurrentData;
        return this;
    }

    /**
     * 设置并行完成回调。
     *
     * <p>并行子流水线执行完毕后触发，参数为父上下文和异步结果。
     * 可用于自定义结果合并逻辑、通知、日志等。</p>
     *
     * @param completionHandler 完成回调
     * @return this
     */
    public TaskParallelDefinition onComplete(BiConsumer<PipelineContext<?>, AsyncResult> completionHandler) {
        this.completionHandler = completionHandler;
        return this;
    }

    /**
     * 设置无返回值的步骤处理器（Consumer 模式）。
     *
     * @param action Consumer 回调
     * @return this
     */
    public TaskParallelDefinition onStep(Consumer<PipelineContext<?>> action) {
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
    public TaskParallelDefinition step(PipelineNode handler) {
        this.preHandler = handler;
        return this;
    }
}
