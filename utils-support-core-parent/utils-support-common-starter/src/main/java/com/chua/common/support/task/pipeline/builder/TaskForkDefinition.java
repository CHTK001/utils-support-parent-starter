package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.core.ForkErrorStrategy;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.node.ForkNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 分叉节点定义 — 类型安全的分叉分支配置构建器。
 *
 * <p>通过 {@link TaskDefinition#fork()} 从任务定义转换而来，
 * 或通过 {@link PipelineBuilder#fork(String)} 直接创建，
 * 通过 {@link #taskEnd()} 完成定义并返回 {@link PipelineBuilder}。</p>
 *
 * <p><strong>方式1：内联定义分支（推荐）</strong></p>
 * <pre>{@code
 * PipelineBuilder.newBuilder("main")
 *     .fork("group")                            // 开始分叉定义
 *         .startFork("a")                       // 内联定义分支 "a"
 *             .step("a1", ctx -> { doA1(ctx); return null; })
 *             .step("a2", ctx -> { doA2(ctx); return null; })
 *         .endFork()                            // 结束分支 "a"
 *         .startFork("b")                       // 内联定义分支 "b"
 *             .step("b1", ctx -> { doB1(ctx); return null; })
 *         .endFork()                            // 结束分支 "b"
 *         .errorStrategy(ForkErrorStrategy.WAIT_ALL)
 *     .taskEnd()                                // 结束分叉定义
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
 *     .fork("group")
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
 *         .fork()                               // 转为分叉定义
 *         .branch("a", branchA)
 *         .branch("b", branchB)
 *     .taskEnd()                                // 结束定义
 *     .build();
 * }</pre>
 *
 * <p><strong>数据合并模型：</strong></p>
 * <ul>
 *   <li>各分支独立执行，互不干扰</li>
 *   <li>各分支结果以 {@link com.chua.common.support.task.pipeline.core.ForkResult} 结构化对象存入 {@code nodeOutputs}</li>
 *   <li>后续节点通过 {@code ctx.getData("fork1", ForkResult.class)} 获取完整结果</li>
 *   <li>分叉节点对外只有一个节点 ID，内部各分支上下文隔离但对父上下文透明</li>
 * </ul>
 *
 * <p><strong>便捷方法：</strong></p>
 * <ul>
 *   <li>{@link #onStep(Consumer)} — 分叉执行前的预处理步骤</li>
 *   <li>{@link #step(PipelineNode)} — 分叉执行前的路由步骤</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 * @see TaskDefinition#fork()
 * @see PipelineBuilder#fork(String)
 * @see ForkNode
 * @see ForkErrorStrategy
 */
public class TaskForkDefinition {

    private final String id;
    private PipelineNode preHandler;
    private final PipelineBuilder builder;
    private final Map<String, Pipeline> branches = new LinkedHashMap<>();
    private ForkErrorStrategy errorStrategy;
    private Map<String, Object> params;
    private Map<String, Object> env;

    /**
     * 构造分叉节点定义。
     *
     * @param id      节点唯一标识
     * @param builder 流水线构建器
     */
    TaskForkDefinition(String id, PipelineBuilder builder) {
        this.id = id;
        this.builder = builder;
    }

    /**
     * 完成定义，将分叉节点添加到流水线，返回构建器继续链式配置。
     *
     * <p>与 {@link TaskDefinition#fork()} 或 {@link PipelineBuilder#fork(String)} 配对使用，
     * 构成完整的分叉定义：taskStart → fork → branch → ... → taskEnd。</p>
     *
     * @return PipelineBuilder
     */
    public PipelineBuilder taskEnd() {
        ForkNode node = new ForkNode(id, branches, errorStrategy);
        if (preHandler != null) {
            node.preHandler(preHandler);
        }
        if (params != null && !params.isEmpty()) {
            node.setParams(params);
        }
        if (env != null && !env.isEmpty()) {
            node.setEnv(env);
        }
        builder.addNodeInternal(node);
        return builder;
    }

    /**
     * 结束当前节点定义并完成整个流水线构建。
     *
     * <p>等价于 {@code .taskEnd().end(id).build()}，一步完成三件事：</p>
     * <ol>
     *   <li>调用 {@link #taskEnd()} 完成当前分叉节点定义</li>
     *   <li>将当前节点标记为流水线终止节点</li>
     *   <li>构建并返回 {@link Pipeline} 实例</li>
     * </ol>
     *
     * <p>适用于流水线最后一个节点是分叉节点的场景。</p>
     *
     * @return 构建完成的 Pipeline 实例
     */
    public Pipeline pipelineEnd() {
        taskEnd();
        builder.end(id);
        return builder.build();
    }

    /**
     * 添加分叉分支。
     *
     * <p>每个分支是一个独立的 {@link Pipeline}，并行执行时各分支通过
     * {@link PipelineContext#createBranchContext()} 创建独立上下文。</p>
     *
     * <p>分支结果存储在父上下文的 {@code nodeOutputs} 中，以 {@link com.chua.common.support.task.pipeline.core.ForkResult}
     * 结构化对象存储，key 为分叉节点的 nodeId。</p>
     *
     * @param name           分支名称（用于结果存储和日志标识）
     * @param branchPipeline 分支流水线实例
     * @return this
     */
    public TaskForkDefinition branch(String name, Pipeline branchPipeline) {
        branches.put(name, branchPipeline);
        return this;
    }

    /**
     * 开始内联定义分支（推荐方式）。
     *
     * <p>返回 {@link ForkBranchBuilder}，支持在分叉定义内部直接定义分支步骤，
     * 无需预先构建 Pipeline 对象。</p>
     *
     * <p><strong>用法示例：</strong></p>
     * <pre>{@code
     * .fork("group")
     *     .startFork("a")                       // 开始定义分支 "a"
     *         .step("a1", ctx -> { doA1(ctx); return null; })
     *         .step("a2", ctx -> { doA2(ctx); return null; })
     *     .endFork()                            // 结束分支 "a"
     *     .startFork("b")                       // 开始定义分支 "b"
     *         .step("b1", ctx -> { doB1(ctx); return null; })
     *     .endFork()                            // 结束分支 "b"
     * .taskEnd()
     * }</pre>
     *
     * <p>对于复杂分支（含判断节点、子流水线等），请使用
     * {@link #branch(String, Pipeline)} 传入预构建的 Pipeline。</p>
     *
     * @param branchName 分支名称（用于结果存储和日志标识）
     * @return ForkBranchBuilder 分支定义构建器
     * @see ForkBranchBuilder#step(String, PipelineNode)
     * @see ForkBranchBuilder#endFork()
     */
    public ForkBranchBuilder startFork(String branchName) {
        return new ForkBranchBuilder(branchName, this);
    }

    /**
     * 结束分叉定义（语义化别名，等价于 {@link #taskEnd()}）。
     *
     * <p>与 {@link #startFork(String)} 配对使用，使分叉定义的开始和结束更加清晰：</p>
     * <pre>{@code
     * .fork("group")
     *     .startFork("a").step("a1", ...).endFork()
     *     .startFork("b").step("b1", ...).endFork()
     * .endFork()    // 等价于 .taskEnd()
     * }</pre>
     *
     * @return PipelineBuilder
     */
    public PipelineBuilder endFork() {
        return taskEnd();
    }

    /**
     * 设置错误处理策略。
     *
     * <p>当分叉执行的某个分支抛出异常时，决定如何处理其他分支：</p>
     * <ul>
     *   <li>{@link ForkErrorStrategy#WAIT_ALL} — 等待所有分支完成，汇总异常（默认）</li>
     *   <li>{@link ForkErrorStrategy#FAIL_FAST} — 第一个失败时立即取消其他分支</li>
     * </ul>
     *
     * @param strategy 错误处理策略
     * @return this
     */
    public TaskForkDefinition errorStrategy(ForkErrorStrategy strategy) {
        this.errorStrategy = strategy;
        return this;
    }

    /**
     * 设置节点参数（JSON 构建时传入，执行时注入到 ctx.nodeLocalData）。
     *
     * @param params 节点参数映射
     * @return this
     */
    public TaskForkDefinition params(Map<String, Object> params) {
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
    public TaskForkDefinition env(Map<String, Object> env) {
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
    public TaskForkDefinition env(String key, Object value) {
        if (this.env == null) {
            this.env = new LinkedHashMap<>();
        }
        this.env.put(key, value);
        return this;
    }

    /**
     * 设置分叉执行前的预处理步骤（Consumer 模式，无返回值）。
     *
     * <p>预处理步骤在所有分叉分支启动前执行，适用于初始化共享数据等场景。</p>
     *
     * @param action Consumer 回调
     * @return this
     */
    public TaskForkDefinition onStep(Consumer<PipelineContext<?>> action) {
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
     * 设置分叉执行前的步骤（Function 模式，有返回值）。
     *
     * <p>适用于分叉执行前需要根据条件决定路由的场景。</p>
     *
     * @param handler PipelineNode 处理器
     * @return this
     */
    public TaskForkDefinition step(PipelineNode handler) {
        this.preHandler = handler;
        return this;
    }

}
