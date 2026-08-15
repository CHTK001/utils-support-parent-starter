package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.core.Action;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.node.SubPipelineNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 子流水线节点定义 — 类型安全的子流水线配置构建器。
 *
 * <p>通过 {@link TaskDefinition#subPipeline(Pipeline)} 从任务定义转换而来，
 * 通过 {@link #taskEnd()} 完成定义并返回 {@link PipelineBuilder}。</p>
 *
 * <p><strong>完整模式：task → subPipeline → ... → taskEnd</strong></p>
 * <pre>{@code
 * Pipeline sub = PipelineBuilder.newBuilder("subFlow")
 *     .task("subStep1", ctx -> { ...; return null; })
 *     .taskEnd()
 *     .build();
 *
 * PipelineBuilder.newBuilder("mainFlow")
 *     .task("sub", ctx -> null)
 *     .subPipeline(sub)           // 转为子流水线定义
 *     .start()                    // 子流水线起始节点
 *     .taskEnd()                  // 结束定义
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
 * </ul>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * // 基本子流水线
 * Pipeline sub = PipelineBuilder.newBuilder("subFlow")
 *     .task("s1", ctx -> { ...; return null; }).taskEnd()
 *     .task("s2", ctx -> { ...; return null; }).taskEnd()
 *     .build();
 *
 * PipelineBuilder.newBuilder("mainFlow")
 *     .task("process", ctx -> null)
 *     .subPipeline(sub)
 *     .taskEnd()
 *     .build();
 *
 * // 指定子流水线起始节点
 * PipelineBuilder.newBuilder("mainFlow")
 *     .task("process", ctx -> null)
 *     .subPipeline(sub)
 *     .start("s2")               // 从子流水线的 s2 节点开始
 *     .taskEnd()
 *     .build();
 *
 * // 带参数的子流水线
 * PipelineBuilder.newBuilder("mainFlow")
 *     .task("process", ctx -> null)
 *     .subPipeline(sub)
 *     .params(Map.of("key", "value"))
 *     .taskEnd()
 *     .build();
 *
 * // 执行后终止
 * PipelineBuilder.newBuilder("mainFlow")
 *     .task("finalStep", ctx -> null)
 *     .subPipeline(sub)
 *     .ext()
 *     .taskEnd()
 *     .build();
 * }</pre>
 *
 * @author CH
 * @see TaskDefinition#subPipeline(Pipeline)
 */
public class TaskSubPipelineDefinition {

    private final String id;
    private PipelineNode preHandler;
    private final PipelineBuilder builder;
    private final Pipeline subPipeline;
    private boolean endAfterExecute;
    private String startNode;
    private Map<String, Object> params;
    private Map<String, Object> env;

    /**
     * 构造子流水线定义。
     *
     * @param id           节点唯一标识
     * @param builder      流水线构建器
     * @param subPipeline  子流水线实例
     */
    TaskSubPipelineDefinition(String id, PipelineBuilder builder, Pipeline subPipeline) {
        this.id = id;
        this.builder = builder;
        this.subPipeline = subPipeline;
    }

    /**
     * 完成定义，将子流水线节点添加到流水线，返回构建器继续链式配置。
     *
     * <p>与 {@link TaskDefinition#subPipeline(Pipeline)} 配对使用，
     * 构成完整的子流水线定义：task → subPipeline → ... → taskEnd。</p>
     *
     * @return PipelineBuilder
     */
    public PipelineBuilder taskEnd() {
        PipelineNode effectivePreHandler = endAfterExecute ? wrapWithEnd(preHandler) : preHandler;
        SubPipelineNode node = new SubPipelineNode(id, subPipeline);
        if (effectivePreHandler != null) {
            node.preHandler(effectivePreHandler);
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
        PipelineNode finalNode = node;
        builder.addNodeInternal(finalNode);
        return builder;
    }

    /**
     * 结束当前节点定义并完成整个流水线构建。
     *
     * <p>等价于 {@code .taskEnd().end(id).build()}，一步完成三件事：</p>
     * <ol>
     *   <li>调用 {@link #taskEnd()} 完成当前子流水线节点定义</li>
     *   <li>将当前节点标记为流水线终止节点</li>
     *   <li>构建并返回 {@link Pipeline} 实例</li>
     * </ol>
     *
     * <p>适用于流水线最后一个节点是子流水线节点的场景。</p>
     *
     * @return 构建完成的 Pipeline 实例
     */
    public Pipeline pipelineEnd() {
        taskEnd();
        builder.end(id);
        return builder.build();
    }

    /**
     * 设置前置处理器（在子流水线执行前调用）。
     *
     * @param handler 前置处理器
     * @return this
     */
    public TaskSubPipelineDefinition preHandler(PipelineNode handler) {
        this.preHandler = handler;
        return this;
    }

    /**
     * 设置子流水线起始节点 ID。
     *
     * <p>如果不设置，子流水线将使用其自身的默认起始节点。</p>
     *
     * @param startNodeId 子流水线中的起始节点 ID
     * @return this
     */
    public TaskSubPipelineDefinition start(String startNodeId) {
        this.startNode = startNodeId;
        return this;
    }

    /**
     * 设置子流水线参数。
     *
     * <p>参数将注入到子流水线上下文中，供子流水线节点访问。</p>
     *
     * @param params 参数映射
     * @return this
     */
    public TaskSubPipelineDefinition params(Map<String, Object> params) {
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
    public TaskSubPipelineDefinition env(Map<String, Object> env) {
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
    public TaskSubPipelineDefinition env(String key, Object value) {
        if (this.env == null) {
            this.env = new LinkedHashMap<>();
        }
        this.env.put(key, value);
        return this;
    }

    /**
     * 设置无返回值的步骤处理器（Consumer 模式）。
     *
     * <p>适用于子流水线执行前需要执行副作用的场景。
     * 自动将 Consumer 包装为返回 null 的 PipelineNode。</p>
     *
     * @param action Consumer 回调
     * @return this
     */
    public TaskSubPipelineDefinition onStep(Consumer<PipelineContext<?>> action) {
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
     * <p>适用于子流水线执行前需要根据条件决定路由的场景。</p>
     *
     * @param handler PipelineNode 处理器
     * @return this
     */
    public TaskSubPipelineDefinition step(PipelineNode handler) {
        this.preHandler = handler;
        return this;
    }

    /**
     * 便捷方法：执行后自动终止流水线（等价于 action=EXIT）。
     *
     * <p>子流水线执行完毕后，主流水线将终止。</p>
     *
     * <p>与 {@link #end()} 完全等价，提供更语义化的命名。</p>
     *
     * @return this
     */
    public TaskSubPipelineDefinition ext() {
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
    public TaskSubPipelineDefinition end() {
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