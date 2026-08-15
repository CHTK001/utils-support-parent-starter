package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;

/**
 * 分叉分支内联定义构建器 — 在分叉定义内部直接定义分支节点。
 *
 * <p>通过 {@link TaskForkDefinition#startFork(String)} 进入分支定义，
 * 通过 {@link #endFork()} 结束分支定义并返回 {@link TaskForkDefinition}。</p>
 *
 * <p><strong>设计原则：主干管外层，分支自己嵌套自己处理。</strong></p>
 * <ul>
 *   <li>分支内部是一个完整的子 Pipeline，支持所有编排能力</li>
 *   <li>简单场景：用 {@link #step} / {@link #task} / {@link #decision} 便捷方法</li>
 *   <li>复杂场景：用 {@link #builder()} 获取内部 PipelineBuilder，做任何编排</li>
 * </ul>
 *
 * <p><strong>简单用法 — 便捷方法（返回 this，链式流畅）：</strong></p>
 * <pre>{@code
 * .fork("group")
 *     .startFork("a")
 *         .step("a1", ctx -> { doA1(ctx); return null; })
 *         .step("a2", ctx -> { doA2(ctx); return null; })
 *     .endFork()
 *     .startFork("b")
 *         .task("b1", ctx -> { doB1(ctx); return null; })
 *     .endFork()
 * .taskEnd()
 * }</pre>
 *
 * <p><strong>复杂用法 — builder() 获取完整编排能力：</strong></p>
 * <pre>{@code
 * .fork("group")
 *     .startFork("a")
 *         .step("a1", ctx -> { doA1(ctx); return null; })
 *         .decision("check", ctx -> condition ? "yes" : "no")  // 判断节点
 *         .task("yes", ctx -> { handleYes(ctx); return null; })
 *         .task("no", ctx -> { handleNo(ctx); return null; })
 *     .endFork()
 *     .startFork("b")
 *         .builder()                                        // 获取内部 PipelineBuilder
 *             .taskStart("b1")
 *                 .onStep(ctx -> init(ctx))
 *                 .exit()                                    // 执行后终止分支
 *             .taskEnd()
 *             .fork("inner-fork")                           // 分支内嵌套分叉！
 *                 .startFork("b2a")
 *                     .step("b2a1", ctx -> { ...; return null; })
 *                 .endFork()
 *             .taskEnd()
 *     .endFork()
 * .taskEnd()
 * }</pre>
 *
 * @author CH
 * @see TaskForkDefinition#startFork(String)
 * @see TaskForkDefinition#endFork()
 */
public class ForkBranchBuilder {

    /** 分支名称 */
    private final String branchName;

    /** 内部构建器，用于构建分支流水线 */
    private final PipelineBuilder innerBuilder;

    /** 父分叉定义，endFork() 时返回 */
    private final TaskForkDefinition parent;

    /**
     * 构造分支定义构建器。
     *
     * @param branchName 分支名称
     * @param parent     父分叉定义
     */
    ForkBranchBuilder(String branchName, TaskForkDefinition parent) {
        this.branchName = branchName;
        this.innerBuilder = PipelineBuilder.newBuilder(branchName);
        this.parent = parent;
    }

    // ========== 便捷方法（返回 this，链式流畅） ==========

    /**
     * 添加任务步骤到当前分支（便捷方法，与 {@link #task} 等价）。
     *
     * <p>每个步骤是一个任务节点，分支内按添加顺序依次执行。</p>
     *
     * @param id      步骤节点唯一标识
     * @param handler 业务逻辑处理器，返回 null 按默认顺序执行，返回节点 ID 则跳转
     * @return this
     */
    public ForkBranchBuilder step(String id, PipelineNode handler) {
        innerBuilder.task(id, handler).taskEnd();
        return this;
    }

    /**
     * 添加任务步骤到当前分支（与 {@link #step} 等价，语义化命名）。
     *
     * @param id      步骤节点唯一标识
     * @param handler 业务逻辑处理器
     * @return this
     */
    public ForkBranchBuilder task(String id, PipelineNode handler) {
        innerBuilder.task(id, handler).taskEnd();
        return this;
    }

    /**
     * 添加判断节点到当前分支。
     *
     * <p>判断节点根据路由回调返回的目标节点 ID 进行跳转。</p>
     *
     * @param id     节点唯一标识
     * @param router 路由处理器，返回目标节点 ID
     * @return this
     */
    public ForkBranchBuilder decision(String id, PipelineNode router) {
        innerBuilder.decision(id, router);
        return this;
    }

    /**
     * 添加子流水线节点到当前分支。
     *
     * @param id          节点唯一标识
     * @param subPipeline 子流水线实例
     * @return this
     */
    public ForkBranchBuilder pipeline(String id, Pipeline subPipeline) {
        innerBuilder.pipeline(id, subPipeline);
        return this;
    }

    /**
     * 添加分叉节点到当前分支（分支内嵌套分叉）。
     *
     * <p>返回 {@link TaskForkDefinition}，支持分支内嵌套分叉编排。
     * 完成嵌套分叉定义后（调用 taskEnd），需通过 {@link #builder()} 继续添加节点，
     * 或直接调用 {@link #endFork()} 结束当前分支。</p>
     *
     * @param id 分叉节点唯一标识
     * @return TaskForkDefinition 嵌套分叉定义
     */
    public TaskForkDefinition fork(String id) {
        return innerBuilder.fork(id);
    }

    // ========== 完整编排能力 ==========

    /**
     * 获取内部 PipelineBuilder，支持完整编排能力。
     *
     * <p><strong>设计原则：主干管外层，分支自己嵌套自己处理。</strong></p>
     * <p>通过 builder() 可以做任何 PipelineBuilder 支持的操作：</p>
     * <ul>
     *   <li>{@code .builder().taskStart(id).onStep(...).exit().taskEnd()} — Definition API</li>
     *   <li>{@code .builder().fork(id).startFork(...)...} — 嵌套分叉</li>
     *   <li>{@code .builder().addListener(...)} — 添加监听器</li>
     *   <li>{@code .builder().routeStrategy(...)} — 配置路由策略</li>
     * </ul>
     *
     * <p>完成内部编排后，调用 {@link #endFork()} 结束当前分支。</p>
     *
     * <p>用法示例：</p>
     * <pre>{@code
     * .startFork("a")
     *     .step("a1", ctx -> { doA1(ctx); return null; })
     *     .builder()                              // 获取内部 builder
     *         .taskStart("a2")
     *             .onStep(ctx -> init(ctx))
     *             .exit()
     *         .taskEnd()
     *         .fork("inner-fork")                 // 嵌套分叉
     *             .startFork("a3a")
     *                 .step("a3a1", ctx -> { ...; return null; })
     *             .endFork()
     *         .taskEnd()
     * .endFork()                                  // 结束分支 "a"
     * }</pre>
     *
     * @return 内部 PipelineBuilder
     */
    public PipelineBuilder builder() {
        return innerBuilder;
    }

    // ========== 结束分支 ==========

    /**
     * 结束分支定义，将构建的分支流水线添加到分叉定义，返回父分叉定义。
     *
     * <p>等价于手动构建 Pipeline 并调用
     * {@link TaskForkDefinition#branch(String, Pipeline)}。</p>
     *
     * @return TaskForkDefinition 父分叉定义
     */
    public TaskForkDefinition endFork() {
        Pipeline branchPipeline = innerBuilder.build();
        parent.branch(branchName, branchPipeline);
        return parent;
    }
}