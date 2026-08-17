package com.chua.common.support.task.pipeline.node;

import com.chua.common.support.task.pipeline.core.AsyncResult;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;

import java.util.*;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.BiConsumer;

/**
 * 并行子流水线节点 — 基于结构化并发（StructuredTaskScope）的非阻塞并行执行。
 *
 * <p>与 {@link SubPipelineNode} 和 {@link ForkNode} 的核心区别：</p>
 * <ul>
 *   <li><strong>SubPipelineNode</strong> — 同步执行，主干阻塞等待子流程完成后才继续</li>
 *   <li><strong>ForkNode</strong> — 分叉+阻塞，多分支并行执行，主干等待所有分支完成</li>
 *   <li><strong>ParallelNode</strong> — 并行+不阻塞，主干不等待，子流程在后台线程执行</li>
 * </ul>
 *
 * <p><strong>执行模型（结构化并发）：</strong></p>
 * <ol>
 *   <li>主干到达并行节点 → 创建独立上下文 → 通过 scope.fork() 提交子流程</li>
 *   <li>主干立即继续执行后续节点（不阻塞等待）</li>
 *   <li>子流程并行执行完毕 → 完成 AsyncResult → 合并结果到父上下文</li>
 *   <li>Pipeline 结束前 scope.join() 保证所有 fork 的并行任务完成</li>
 * </ol>
 *
 * <p><strong>结构化并发保证：</strong></p>
 * <p>并行任务通过 {@link StructuredTaskScope#fork} 提交到 Pipeline 级别的 scope 中，
 * 由 {@code DefaultPipeline.executeWith()} 统一管理生命周期。
 * Pipeline 返回前调用 {@code scope.join()} 确保所有并行子流水线完成，
 * 不会出现孤儿线程或结果丢失。</p>
 *
 * <p><strong>结果存储：</strong></p>
 * <p>并行节点执行时，立即在 {@code nodeOutputs} 中存入初始 {@link AsyncResult}（completed=false），
 * 后续节点可通过 {@code ctx.getData("parallelStep", AsyncResult.class)} 获取并行结果句柄，
 * 调用 {@link AsyncResult#await()} 阻塞等待完成，或通过 {@link AsyncResult#isCompleted()} 非阻塞检查。</p>
 *
 * <pre>
 * nodeOutputs["parallelStep"] = AsyncResult {
 *     nodeId: "parallelStep",
 *     output: data,               // 并行子流水线的最终输出（完成后可用）
 *     history: ["a1", "a2"],      // 并行子流水线的执行历史
 *     pipelineId: "parallelSub",  // 并行子流水线 ID
 *     completed: true,            // 是否已完成
 *     error: null                 // 异常（如果失败）
 * }
 * </pre>
 *
 * <p><strong>结果合并：</strong></p>
 * <p>并行子流水线执行完毕后，结果自动合并到父上下文：</p>
 * <ul>
 *   <li>{@code nodeOutputs} 中的 AsyncResult 更新为完成状态</li>
 *   <li>{@code currentData} 默认更新为并行输出（mergeCurrentData 默认 true）</li>
 *   <li>触发 {@link #completionHandler}（如果配置）</li>
 * </ul>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * // 方式1：PipelineBuilder 直接构建
 * Pipeline parallelSub = PipelineBuilder.newBuilder("parallelSub")
 *     .task("a1", ctx -> { doA1(ctx); return null; }).taskEnd()
 *     .task("a2", ctx -> { doA2(ctx); return null; }).taskEnd()
 *     .build();
 *
 * Pipeline mainPipeline = PipelineBuilder.newBuilder("main")
 *     .task("init", ctx -> { init(ctx); return null; }).taskEnd()
 *     .parallel("parallelStep", parallelSub)             // 并行执行，主干不等待
 *     .task("continue", ctx -> { continue(ctx); return null; }).taskEnd()
 *     .build();
 *
 * // 方式2：Definition API
 * Pipeline mainPipeline = PipelineBuilder.newBuilder("main")
 *     .task("init", ctx -> { init(ctx); return null; }).taskEnd()
 *     .task("parallelStep", ctx -> null)
 *         .parallel(parallelSub)                         // 转为并行子流水线定义
 *         .onComplete((ctx, result) -> {                 // 完成回调
 *             log.info("Parallel completed: {}", result.getOutput());
 *         })
 *         .taskEnd()
 *     .task("continue", ctx -> { continue(ctx); return null; }).taskEnd()
 *     .build();
 *
 * // 后续节点获取并行结果
 * .task("check", ctx -> {
 *     AsyncResult result = ctx.getData("parallelStep", AsyncResult.class);
 *     if (result.isCompleted()) {
 *         Object data = result.getOutput();
 *     } else {
 *         result.await();  // 阻塞等待
 *     }
 *     return null;
 * }).taskEnd()
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see AsyncResult
 * @see SubPipelineNode
 * @see ForkNode
 */
public class ParallelNode implements PipelineNode {

    /**
     * 内部属性键 — StructuredTaskScope 实例。
     *
     * <p>与 {@code DefaultPipeline.ATTR_PIPELINE_SCOPE} 保持一致，
     * 通过 PipelineContext.attributes 传递 Pipeline 级别的 StructuredTaskScope。</p>
     */
    private static final String ATTR_PIPELINE_SCOPE = "__pipelineScope__";

    /**
     * 节点唯一标识
     */
    private final String id;

    /**
     * 并行子流水线实例
     */
    private final Pipeline subPipeline;

    /**
     * 并行完成后是否将输出合并到父上下文的 currentData，默认 true。
     *
     * <p>并行子流程的结果必须合并回主干，否则后续节点无法获取异步执行的结果。
     * 默认启用合并，确保数据流完整性。</p>
     *
     * <p>注意：并行完成时主干可能已在其他节点，合并 currentData 可能覆盖当前节点的数据。
     * 如需自定义合并逻辑，可通过 completionHandler 手动处理。</p>
     */
    private boolean mergeCurrentData = true;

    /**
     * 并行完成回调（可选）
     *
     * <p>并行子流水线执行完毕后触发，参数为父上下文和异步结果。
     * 可用于自定义结果合并逻辑、通知、日志等。</p>
     */
    private BiConsumer<PipelineContext<?>, AsyncResult> completionHandler;

    /**
     * 前置处理器（在并行子流水线启动前调用，可选）
     */
    private PipelineNode preHandler;

    /**
     * 子流水线起始节点 ID（覆盖默认起始节点，可选）
     */
    private String startNode;

    /**
     * 子流水线参数（注入到子上下文的 nodeLocalData，可选）
     */
    private Map<String, Object> params;

    /**
     * 节点环境参数映射（定义时配置，运行时环境配置如模型路径、阈值等）
     */
    private Map<String, Object> env;

    /**
     * 构造并行子流水线节点。
     *
     * @param id          节点唯一标识
     * @param subPipeline 并行子流水线实例
     */
    public ParallelNode(String id, Pipeline subPipeline) {
        this.id = id;
        this.subPipeline = subPipeline;
    }

    /**
     * 获取节点 ID。
     *
     * @return 节点 ID
     */
    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getType() {
        return "parallel";
    }

    /**
     * 获取并行子流水线 ID。
     *
     * @return 子流水线 ID
     */
    public String getSubPipelineId() {
        return subPipeline.getId();
    }

    /**
     * 获取并行子流水线实例。
     *
     * @return 子流水线 Pipeline 实例
     */
    public Pipeline getSubPipeline() {
        return subPipeline;
    }

    /**
     * 设置并行完成后是否合并 currentData。
     *
     * @param mergeCurrentData true 表示并行完成后将输出写回父上下文的 currentData
     * @return this
     */
    public ParallelNode mergeCurrentData(boolean mergeCurrentData) {
        this.mergeCurrentData = mergeCurrentData;
        return this;
    }

    /**
     * 获取并行完成后是否合并 currentData。
     *
     * @return true 表示并行完成后将输出写回父上下文的 currentData
     */
    public boolean isMergeCurrentData() {
        return mergeCurrentData;
    }

    /**
     * 设置并行完成回调。
     *
     * @param completionHandler 完成回调，参数为 (父上下文, 异步结果)
     * @return this
     */
    public ParallelNode onComplete(BiConsumer<PipelineContext<?>, AsyncResult> completionHandler) {
        this.completionHandler = completionHandler;
        return this;
    }

    /**
     * 获取并行完成回调。
     *
     * @return 完成回调，未设置时返回 null
     */
    public BiConsumer<PipelineContext<?>, AsyncResult> getCompletionHandler() {
        return completionHandler;
    }

    /**
     * 设置前置处理器。
     *
     * @param preHandler 前置处理器
     * @return this
     */
    public ParallelNode preHandler(PipelineNode preHandler) {
        this.preHandler = preHandler;
        return this;
    }

    /**
     * 获取前置处理器。
     *
     * @return 前置处理器，未设置时返回 null
     */
    public PipelineNode getPreHandler() {
        return preHandler;
    }

    /**
     * 设置子流水线起始节点 ID。
     *
     * @param startNode 起始节点 ID
     * @return this
     */
    public ParallelNode start(String startNode) {
        this.startNode = startNode;
        return this;
    }

    /**
     * 获取子流水线起始节点 ID。
     *
     * @return 起始节点 ID，未设置时返回 null
     */
    public String getStartNode() {
        return startNode;
    }

    /**
     * 设置子流水线参数。
     *
     * @param params 参数映射
     * @return this
     */
    public ParallelNode params(Map<String, Object> params) {
        this.params = params != null ? new LinkedHashMap<>(params) : null;
        return this;
    }

    /**
     * 获取子流水线参数。
     *
     * @return 参数映射，未设置时返回空 Map
     */
    public Map<String, Object> getParams() {
        return params != null ? params : Collections.emptyMap();
    }

    /**
     * 设置节点环境参数（定义时调用）。
     *
     * @param env 环境参数映射
     */
    public void setEnv(Map<String, Object> env) {
        this.env = env != null ? env : Collections.emptyMap();
    }

    @Override
    public Map<String, Object> getEnv() {
        return env != null ? env : Collections.emptyMap();
    }

    /**
     * 并行执行子流水线（结构化并发版）。
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>设置当前节点 ID</li>
     *   <li>执行前置处理器（如果配置）</li>
     *   <li>创建独立上下文</li>
     *   <li>从 PipelineContext.attributes 获取 StructuredTaskScope</li>
     *   <li>scope.fork() 提交子流水线到结构化并发作用域</li>
     *   <li>立即在 nodeOutputs 存入初始 AsyncResult（completed=false）</li>
     *   <li>返回 null，主干继续执行后续节点</li>
     *   <li>fork 内：执行子流水线 → 完成 AsyncResult → 合并结果到父上下文</li>
     * </ol>
     *
     * <p><strong>降级策略：</strong>若 attributes 中无 StructuredTaskScope（非 Pipeline 级调用），
     * 则同步执行子流水线，确保功能正确但无并发收益。</p>
     *
     * @param context 父流水线上下文
     * @return null，按默认顺序继续执行下一节点
     */
    @Override
    @SuppressWarnings("unchecked")
    public String execute(PipelineContext<?> context) {
        context.setCurrentNodeId(id);

        // 1. 执行前置处理器（如果配置）
        if (preHandler != null) {
            preHandler.execute(context);
        }

        // 2. 创建子上下文 — 通过 createBranchContext 共享 attributes/nodeOutputs（与 ForkNode 一致）
        //    共享 attributes 使子流水线继承父 Pipeline 的 StructuredTaskScope，
        //    executeWith 的嵌套场景分支直接复用父 scope 运行，无需创建嵌套 scope
        PipelineContext<Object> subCtx =
                context.createBranchContext(subPipeline.getId(), context.getCurrentData());
        if (startNode != null) {
            subCtx.setNextNodeId(startNode);
        }
        if (params != null && !params.isEmpty()) {
            Map<String, Object> localData = subCtx.getNodeLocalData();
            localData.putAll(params);
        }

        // 3. 创建 AsyncResult（初始未完成状态）
        AsyncResult asyncResult = new AsyncResult(id, subPipeline.getId());
        context.setNodeOutput(id, asyncResult);

        // 4. 获取 Pipeline 级 StructuredTaskScope（Java 25 API：StructuredTaskScope<Object, Void>）
        StructuredTaskScope<Object, Void> scope =
                (StructuredTaskScope<Object, Void>) context.getAttributes().get(ATTR_PIPELINE_SCOPE);

        if (scope != null) {
            // 结构化并发：fork 到 Pipeline 级 scope
            final PipelineContext<Object> finalSubCtx = subCtx;
            scope.fork(() -> {
                try {
                    subPipeline.execute(finalSubCtx);
                    // 正常完成：用子流水线上下文的结果填充 AsyncResult
                    asyncResult.complete(finalSubCtx.getCurrentData(), finalSubCtx.getHistory());
                } catch (Exception e) {
                    // 异常完成
                    asyncResult.completeWithError(e);
                }
                // 合并结果到父上下文
                mergeResultToParent(context, asyncResult);
            });
        } else {
            // 降级：无 scope 时同步执行（确保功能正确但无并发收益）
            try {
                subPipeline.execute(subCtx);
                asyncResult.complete(subCtx.getCurrentData(), subCtx.getHistory());
            } catch (Exception e) {
                asyncResult.completeWithError(e);
            }
            mergeResultToParent(context, asyncResult);
        }

        // 5. 主干不等待，继续执行后续节点
        return null;
    }

    /**
     * 并行完成后合并结果到父上下文。
     *
     * <p>合并操作：</p>
     * <ul>
     *   <li>AsyncResult 已在 fork 内完成（output + history）</li>
     *   <li>如果 mergeCurrentData=true（默认），更新父上下文的 currentData</li>
     *   <li>触发 completionHandler（如果配置）</li>
     * </ul>
     *
     * @param parentCtx   父上下文
     * @param asyncResult 异步结果（已完成）
     */
    @SuppressWarnings("unchecked")
    private void mergeResultToParent(PipelineContext<?> parentCtx, AsyncResult asyncResult) {
        // mergeCurrentData：将并行输出写回父上下文的 currentData（默认 true）
        if (mergeCurrentData && asyncResult.isCompleted() && !asyncResult.isFailed()) {
            PipelineContext<Object> ctx = (PipelineContext<Object>) parentCtx;
            ctx.setCurrentData(asyncResult.getOutput());
        }

        // 触发完成回调
        if (completionHandler != null) {
            completionHandler.accept(parentCtx, asyncResult);
        }
    }
}
