package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.core.Action;
import com.chua.common.support.task.pipeline.core.ParallelErrorStrategy;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.node.ParallelNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 并行节点定义 — 类型安全的并行分支配置构建器。
 *
 * <p>通过 {@link TaskDefinition#parallel()} 从任务定义转换而来，
 * 或通过 {@link PipelineBuilder#parallel(String)} 直接创建，
 * 通过 {@link #taskEnd()} 完成定义并返回 {@link PipelineBuilder}。</p>
 *
 * <p><strong>方式1：内联定义分支（推荐）</strong></p>
 * <pre>{@code
 * PipelineBuilder.newBuilder("main")
 *     .parallel("group")                        // 开始并行定义
 *         .startParallel("a")                   // 内联定义分支 "a"
 *             .step("a1", ctx -> { doA1(ctx); return null; })
 *             .step("a2", ctx -> { doA2(ctx); return null; })
 *         .endParallel()                        // 结束分支 "a"
 *         .startParallel("b")                   // 内联定义分支 "b"
 *             .step("b1", ctx -> { doB1(ctx); return null; })
 *         .endParallel()                        // 结束分支 "b"
 *         .errorStrategy(ParallelErrorStrategy.WAIT_ALL)
 *     .endParallel()                            // 结束并行定义
 *     .build();
 * }</pre>
 *
 * <p><strong>方式2：预构建 Pipeline 传入</strong></p>
 * <pre>{@code
 * Pipeline branchA = PipelineBuilder.newBuilder("branchA")
 *     .task("a1", ctx -> { doA1(ctx); return null; }).taskEnd()
 *     .build();
 *
 * Pipeline branchB = PipelineBuilder.newBuilder("branchB")
 *     .task("b1", ctx -> { doB1(ctx); return null; }).taskEnd()
 *     .build();
 *
 * PipelineBuilder.newBuilder("main")
 *     .parallel("group")
 *         .branch("a", branchA)
 *         .branch("b", branchB)
 *     .taskEnd()
 *     .build();
 * }</pre>
 *
 * <p><strong>方式3：从 taskStart 转换</strong></p>
 * <pre>{@code
 * PipelineBuilder.newBuilder("main")
 *     .taskStart("group")
 *         .parallel()                           // 转为并行定义
 *         .branch("a", branchA)
 *         .branch("b", branchB)
 *     .taskEnd()                                // 结束定义
 *     .build();
 * }</pre>
 *
 * <p><strong>数据合并模型：</strong></p>
 * <ul>
 *   <li>各分支独立执行，互不干扰</li>
 *   <li>各分支结果存入 {@code nodeOutputs}，key 为 {@code parallel:{nodeId}:{branchName}}</li>
 *   <li>后续节点通过 {@code ctx.getNodeOutput("parallel:{nodeId}:{branchName}")} 获取分支输出</li>
 *   <li>并行节点对外只有一个节点 ID，内部各分支上下文隔离但对父上下文透明</li>
 * </ul>
 *
 * <p><strong>便捷方法：</strong></p>
 * <ul>
 *   <li>{@link #onStep(Consumer)} — 并行执行前的预处理步骤</li>
 *   <li>{@link #step(PipelineNode)} — 并行执行前的路由步骤</li>
 *   <li>{@link #ext()} — 所有分支完成后终止主流水线</li>
 *   <li>{@link #end()} — 同 {@link #ext()}</li>
 * </ul>
 *
 * @author CH
 * @see TaskDefinition#parallel()
 * @see PipelineBuilder#parallel(String)
 * @see ParallelNode
 * @see ParallelErrorStrategy
 */
public class TaskParallelDefinition {

    private final String id;
    private PipelineNode preHandler;
    private final PipelineBuilder builder;
    private final Map<String, Pipeline> branches = new LinkedHashMap<>();
    private ParallelErrorStrategy errorStrategy;
    private boolean endAfterExecute;
    private Map<String, Object> params;
    private Map<String, Object> env;
    private Predicate<PipelineContext<?>> condition;

    /**
     * 构造并行节点定义。
     *
     * @param id      节点唯一标识
     * @param builder 流水线构建器
     */
    TaskParallelDefinition(String id, PipelineBuilder builder) {
        this.id = id;
        this.builder = builder;
    }

    /**
     * 完成定义，将并行节点添加到流水线，返回构建器继续链式配置。
     *
     * <p>与 {@link TaskDefinition#parallel()} 或 {@link PipelineBuilder#parallel(String)} 配对使用，
     * 构成完整的并行定义：taskStart → parallel → branch → ... → taskEnd。</p>
     *
     * @return PipelineBuilder
     */
    public PipelineBuilder taskEnd() {
        ParallelNode node = new ParallelNode(id, branches, errorStrategy);
        if (preHandler != null) {
            node.preHandler(preHandler);
        }
        if (params != null && !params.isEmpty()) {
            node.setParams(params);
        }
        if (env != null && !env.isEmpty()) {
            node.setEnv(env);
        }
        PipelineNode finalNode = node;
        // 条件执行包装
        if (condition != null) {
            Predicate<PipelineContext<?>> cond = condition;
            PipelineNode originalNode = finalNode;
            finalNode = new PipelineNode() {
                @Override
                public String execute(PipelineContext<?> context) {
                    if (cond.test(context)) {
                        return originalNode.execute(context);
                    }
                    return null;
                }
                @Override
                public String getId() {
                    return id;
                }
            };
        }
        // 执行后终止包装
        if (endAfterExecute) {
            PipelineNode originalNode = finalNode;
            finalNode = new PipelineNode() {
                @Override
                public String execute(PipelineContext<?> context) {
                    String result = originalNode.execute(context);
                    context.setAction(Action.EXIT);
                    return null;
                }
                @Override
                public String getId() {
                    return id;
                }
            };
        }
        builder.addNodeInternal(finalNode);
        return builder;
    }

    /**
     * 结束当前节点定义并完成整个流水线构建。
     *
     * <p>等价于 {@code .taskEnd().end(id).build()}，一步完成三件事：</p>
     * <ol>
     *   <li>调用 {@link #taskEnd()} 完成当前并行节点定义</li>
     *   <li>将当前节点标记为流水线终止节点</li>
     *   <li>构建并返回 {@link Pipeline} 实例</li>
     * </ol>
     *
     * <p>适用于流水线最后一个节点是并行节点的场景。</p>
     *
     * @return 构建完成的 Pipeline 实例
     */
    public Pipeline pipelineEnd() {
        taskEnd();
        builder.end(id);
        return builder.build();
    }

    /**
     * 添加并行分支。
     *
     * <p>每个分支是一个独立的 {@link Pipeline}，并行执行时各分支通过
     * {@link PipelineContext#createBranchContext()} 创建独立上下文。</p>
     *
     * <p>分支结果存储在父上下文的 {@code nodeOutputs} 中，key 为
     * {@code parallel:{nodeId}:{branchName}}，后续节点可通过
     * {@code ctx.getNodeOutput("parallel:{nodeId}:{branchName}")} 获取。</p>
     *
     * @param name         分支名称（用于结果存储和日志标识）
     * @param branchPipeline 分支流水线实例
     * @return this
     */
    public TaskParallelDefinition branch(String name, Pipeline branchPipeline) {
        branches.put(name, branchPipeline);
        return this;
    }

    /**
     * 开始内联定义分支（推荐方式）。
     *
     * <p>返回 {@link ParallelBranchBuilder}，支持在并行定义内部直接定义分支步骤，
     * 无需预先构建 Pipeline 对象。</p>
     *
     * <p><strong>用法示例：</strong></p>
     * <pre>{@code
     * .parallel("group")
     *     .startParallel("a")                   // 开始定义分支 "a"
     *         .step("a1", ctx -> { doA1(ctx); return null; })
     *         .step("a2", ctx -> { doA2(ctx); return null; })
     *     .endParallel()                        // 结束分支 "a"
     *     .startParallel("b")                   // 开始定义分支 "b"
     *         .step("b1", ctx -> { doB1(ctx); return null; })
     *     .endParallel()                        // 结束分支 "b"
     * .taskEnd()
     * }</pre>
     *
     * <p>对于复杂分支（含判断节点、子流水线等），请使用
     * {@link #branch(String, Pipeline)} 传入预构建的 Pipeline。</p>
     *
     * @param branchName 分支名称（用于结果存储和日志标识）
     * @return ParallelBranchBuilder 分支定义构建器
     * @see ParallelBranchBuilder#step(String, PipelineNode)
     * @see ParallelBranchBuilder#endParallel()
     */
    public ParallelBranchBuilder startParallel(String branchName) {
        return new ParallelBranchBuilder(branchName, this);
    }

    /**
     * 结束并行定义（语义化别名，等价于 {@link #taskEnd()}）。
     *
     * <p>与 {@link #startParallel(String)} 配对使用，使并行定义的开始和结束更加清晰：</p>
     * <pre>{@code
     * .parallel("group")
     *     .startParallel("a").step("a1", ...).endParallel()
     *     .startParallel("b").step("b1", ...).endParallel()
     * .endParallel()    // 等价于 .taskEnd()
     * }</pre>
     *
     * @return PipelineBuilder
     */
    public PipelineBuilder endParallel() {
        return taskEnd();
    }

    /**
     * 设置错误处理策略。
     *
     * <p>当并行执行的某个分支抛出异常时，决定如何处理其他分支：</p>
     * <ul>
     *   <li>{@link ParallelErrorStrategy#WAIT_ALL} — 等待所有分支完成，汇总异常（默认）</li>
     *   <li>{@link ParallelErrorStrategy#FAIL_FAST} — 第一个失败时立即取消其他分支</li>
     * </ul>
     *
     * @param strategy 错误处理策略
     * @return this
     */
    public TaskParallelDefinition errorStrategy(ParallelErrorStrategy strategy) {
        this.errorStrategy = strategy;
        return this;
    }

    /**
     * 设置节点参数（JSON 构建时传入，执行时注入到 ctx.nodeLocalData）。
     *
     * @param params 节点参数映射
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
     *   <li><strong>params</strong> — 静态参数，注入到 nodeLocalData 根级</li>
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
     * 条件执行：仅当谓词返回 true 时执行并行节点，否则跳过。
     *
     * <p>当条件不满足时，所有并行分支均不执行，节点返回 null 按默认顺序继续。</p>
     *
     * @param condition 执行条件谓词
     * @return this
     */
    public TaskParallelDefinition when(Predicate<PipelineContext<?>> condition) {
        this.condition = condition;
        return this;
    }

    /**
     * 条件执行：仅当谓词返回 false 时执行并行节点，否则跳过。
     *
     * @param condition 跳过条件谓词
     * @return this
     */
    public TaskParallelDefinition whenNot(Predicate<PipelineContext<?>> condition) {
        this.condition = condition.negate();
        return this;
    }

    /**
     * 设置并行执行前的预处理步骤（Consumer 模式，无返回值）。
     *
     * <p>预处理步骤在所有并行分支启动前执行，适用于初始化共享数据等场景。</p>
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
     * 设置并行执行前的步骤（Function 模式，有返回值）。
     *
     * <p>适用于并行执行前需要根据条件决定路由的场景。</p>
     *
     * @param handler PipelineNode 处理器
     * @return this
     */
    public TaskParallelDefinition step(PipelineNode handler) {
        this.preHandler = handler;
        return this;
    }

    /**
     * 便捷方法：所有分支完成后自动终止主流水线（等价于 action=EXIT）。
     *
     * <p>与 {@link #end()} 完全等价，提供更语义化的命名。</p>
     *
     * @return this
     */
    public TaskParallelDefinition ext() {
        this.endAfterExecute = true;
        return this;
    }

    /**
     * 便捷方法：所有分支完成后自动终止主流水线。
     *
     * <p>与 {@link #ext()} 完全等价。</p>
     *
     * @return this
     */
    public TaskParallelDefinition end() {
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