package com.chua.common.support.task.pipeline.node;

import com.chua.common.support.task.pipeline.core.AsyncResult;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * 异步子流水线节点 — 非阻塞异步执行子 Pipeline。
 *
 * <p>与 {@link SubPipelineNode} 的核心区别：</p>
 * <ul>
 *   <li><strong>SubPipelineNode</strong> — 同步执行，主干阻塞等待子流程完成后才继续</li>
 *   <li><strong>AsyncSubPipelineNode</strong> — 异步执行，主干不等待，子流程在后台线程执行</li>
 * </ul>
 *
 * <p><strong>执行模型：</strong></p>
 * <ol>
 *   <li>主干到达异步节点 → 创建独立上下文 → 提交子流程到异步线程</li>
 *   <li>主干立即继续执行后续节点（不阻塞等待）</li>
 *   <li>子流程异步执行完毕 → 回调合并结果到父上下文</li>
 * </ol>
 *
 * <p><strong>结果存储：</strong></p>
 * <p>异步节点执行时，立即在 {@code nodeOutputs} 中存入初始 {@link AsyncResult}（completed=false），
 * 后续节点可通过 {@code ctx.getData("asyncStep", AsyncResult.class)} 获取异步结果句柄，
 * 调用 {@link AsyncResult#await()} 阻塞等待完成，或通过 {@link AsyncResult#isCompleted()} 非阻塞检查。</p>
 *
 * <pre>
 * nodeOutputs["asyncStep"] = AsyncResult {
 *     nodeId: "asyncStep",
 *     output: data,               // 异步子流水线的最终输出（完成后可用）
 *     history: ["a1", "a2"],      // 异步子流水线的执行历史
 *     pipelineId: "asyncSub",     // 异步子流水线 ID
 *     completed: true,            // 是否已完成
 *     error: null                 // 异常（如果失败）
 * }
 * </pre>
 *
 * <p><strong>回调合并：</strong></p>
 * <p>异步子流水线执行完毕后，结果自动合并到父上下文：</p>
 * <ul>
 *   <li>{@code nodeOutputs} 中的 AsyncResult 更新为完成状态</li>
 *   <li>{@code currentData} 可选更新为异步输出（由 {@link #mergeCurrentData} 控制）</li>
 *   <li>触发 {@link #completionHandler}（如果配置）</li>
 * </ul>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * // 方式1：PipelineBuilder 直接构建
 * Pipeline asyncSub = PipelineBuilder.newBuilder("asyncSub")
 *     .task("a1", ctx -> { doA1(ctx); return null; }).taskEnd()
 *     .task("a2", ctx -> { doA2(ctx); return null; }).taskEnd()
 *     .build();
 *
 * Pipeline mainPipeline = PipelineBuilder.newBuilder("main")
 *     .task("init", ctx -> { init(ctx); return null; }).taskEnd()
 *     .async("asyncStep", asyncSub)                    // 异步执行，主干不等待
 *     .task("continue", ctx -> { continue(ctx); return null; }).taskEnd()
 *     .build();
 *
 * // 方式2：Definition API
 * Pipeline mainPipeline = PipelineBuilder.newBuilder("main")
 *     .task("init", ctx -> { init(ctx); return null; }).taskEnd()
 *     .task("asyncStep", ctx -> null)
 *         .async(asyncSub)                             // 转为异步子流水线定义
 *         .mergeCurrentData(true)                      // 完成后回写 currentData
 *         .onComplete((ctx, result) -> {               // 完成回调
 *             log.info("Async completed: {}", result.getOutput());
 *         })
 *         .taskEnd()
 *     .task("continue", ctx -> { continue(ctx); return null; }).taskEnd()
 *     .build();
 *
 * // 后续节点获取异步结果
 * .task("check", ctx -> {
 *     AsyncResult result = ctx.getData("asyncStep", AsyncResult.class);
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
 * @see AsyncResult
 * @see SubPipelineNode
 */
public class AsyncSubPipelineNode implements PipelineNode {

    /**
     * 节点唯一标识
     */
    private final String id;

    /**
     * 异步子流水线实例
     */
    private final Pipeline subPipeline;

    /**
     * 自定义线程池（可选，null 时使用 ForkJoinPool.commonPool()）
     */
    private final Executor executor;

    /**
     * 异步完成后是否将输出合并到父上下文的 currentData，默认 false
     *
     * <p>注意：异步完成时主干可能已在其他节点，合并 currentData 可能覆盖当前节点的数据。
     * 建议仅在确定安全时启用，或通过 completionHandler 手动合并。</p>
     */
    private boolean mergeCurrentData;

    /**
     * 异步完成回调（可选）
     *
     * <p>异步子流水线执行完毕后触发，参数为父上下文和异步结果。
     * 可用于自定义结果合并逻辑、通知、日志等。</p>
     */
    private BiConsumer<PipelineContext<?>, AsyncResult> completionHandler;

    /**
     * 前置处理器（在异步子流水线启动前调用，可选）
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
     * 构造异步子流水线节点（使用默认线程池）。
     *
     * @param id          节点唯一标识
     * @param subPipeline 异步子流水线实例
     */
    public AsyncSubPipelineNode(String id, Pipeline subPipeline) {
        this(id, subPipeline, null);
    }

    /**
     * 构造异步子流水线节点（自定义线程池）。
     *
     * @param id          节点唯一标识
     * @param subPipeline 异步子流水线实例
     * @param executor    自定义线程池，null 时使用 ForkJoinPool.commonPool()
     */
    public AsyncSubPipelineNode(String id, Pipeline subPipeline, Executor executor) {
        this.id = id;
        this.subPipeline = subPipeline;
        this.executor = executor;
        this.mergeCurrentData = false;
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
        return "async";
    }

    /**
     * 获取异步子流水线 ID。
     *
     * @return 子流水线 ID
     */
    public String getSubPipelineId() {
        return subPipeline.getId();
    }

    /**
     * 获取异步子流水线实例。
     *
     * @return 子流水线 Pipeline 实例
     */
    public Pipeline getSubPipeline() {
        return subPipeline;
    }

    /**
     * 获取自定义线程池。
     *
     * @return 线程池，null 表示使用默认 ForkJoinPool.commonPool()
     */
    public Executor getExecutor() {
        return executor;
    }

    /**
     * 设置异步完成后是否合并 currentData。
     *
     * @param mergeCurrentData true 表示异步完成后将输出写回父上下文的 currentData
     * @return this
     */
    public AsyncSubPipelineNode mergeCurrentData(boolean mergeCurrentData) {
        this.mergeCurrentData = mergeCurrentData;
        return this;
    }

    /**
     * 获取异步完成后是否合并 currentData。
     *
     * @return true 表示异步完成后将输出写回父上下文的 currentData
     */
    public boolean isMergeCurrentData() {
        return mergeCurrentData;
    }

    /**
     * 设置异步完成回调。
     *
     * @param completionHandler 完成回调，参数为 (父上下文, 异步结果)
     * @return this
     */
    public AsyncSubPipelineNode onComplete(BiConsumer<PipelineContext<?>, AsyncResult> completionHandler) {
        this.completionHandler = completionHandler;
        return this;
    }

    /**
     * 获取异步完成回调。
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
    public AsyncSubPipelineNode preHandler(PipelineNode preHandler) {
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
    public AsyncSubPipelineNode start(String startNode) {
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
    public AsyncSubPipelineNode params(Map<String, Object> params) {
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
     * 异步执行子流水线。
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>设置当前节点 ID</li>
     *   <li>执行前置处理器（如果配置）</li>
     *   <li>创建独立上下文</li>
     *   <li>提交子流水线到异步线程</li>
     *   <li>立即在 nodeOutputs 存入初始 AsyncResult（completed=false）</li>
     *   <li>返回 null，主干继续执行后续节点</li>
     *   <li>异步完成后回调合并结果</li>
     * </ol>
     *
     * @param context 父流水线上下文
     * @return null，按默认顺序继续执行下一节点
     */
    @Override
    public String execute(PipelineContext<?> context) {
        context.setCurrentNodeId(id);

        // 1. 执行前置处理器（如果配置）
        if (preHandler != null) {
            preHandler.execute(context);
        }

        // 2. 创建独立上下文（与 SubPipelineNode 一致）
        PipelineContext<Object> subCtx;
        if (startNode != null || (params != null && !params.isEmpty())) {
            subCtx = new PipelineContext<>(subPipeline.getId(), context.getCurrentData());
            if (startNode != null) {
                subCtx.setNextNodeId(startNode);
            }
            if (params != null && !params.isEmpty()) {
                Map<String, Object> localData = subCtx.getNodeLocalData();
                localData.putAll(params);
            }
        } else {
            subCtx = new PipelineContext<>(subPipeline.getId(), context.getCurrentData());
        }

        // 3. 提交异步执行
        final PipelineContext<Object> finalSubCtx = subCtx;
        CompletableFuture<Void> future;
        if (executor != null) {
            future = CompletableFuture.runAsync(() -> executeSubPipeline(finalSubCtx), executor);
        } else {
            future = CompletableFuture.runAsync(() -> executeSubPipeline(finalSubCtx));
        }

        // 4. 立即存入初始 AsyncResult（completed=false）
        AsyncResult asyncResult = new AsyncResult(id, subPipeline.getId(), future);
        context.setNodeOutput(id, asyncResult);

        // 5. 注册完成回调：异步完成后合并结果到父上下文
        //    注意：使用 finalSubCtx（子流水线上下文）获取异步执行结果
        future.whenComplete((v, throwable) -> {
            if (throwable != null) {
                // 异步执行异常
                Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                        ? throwable.getCause() : throwable;
                asyncResult.completeWithError(cause);
            } else {
                // 正常完成：用子流水线上下文的结果填充 AsyncResult
                asyncResult.complete(finalSubCtx.getCurrentData(), finalSubCtx.getHistory());
            }
            // 合并结果到父上下文
            mergeResultToParent(context, asyncResult);
        });

        // 6. 主干不等待，继续执行后续节点
        return null;
    }

    /**
     * 在异步线程中执行子流水线。
     *
     * @param subCtx 子流水线上下文
     */
    private void executeSubPipeline(PipelineContext<Object> subCtx) {
        try {
            subPipeline.execute(subCtx);
        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * 异步完成后合并结果到父上下文。
     *
     * <p>合并操作：</p>
     * <ul>
     *   <li>AsyncResult 已在 whenComplete 回调中完成（output + history）</li>
     *   <li>如果 mergeCurrentData=true，更新父上下文的 currentData</li>
     *   <li>触发 completionHandler（如果配置）</li>
     * </ul>
     *
     * @param parentCtx   父上下文
     * @param asyncResult 异步结果（已完成）
     */
    @SuppressWarnings("unchecked")
    private void mergeResultToParent(PipelineContext<?> parentCtx, AsyncResult asyncResult) {
        // mergeCurrentData：将异步输出写回父上下文的 currentData
        if (mergeCurrentData && asyncResult.isCompleted() && !asyncResult.isFailed()) {
            @SuppressWarnings("unchecked")
            PipelineContext<Object> ctx = (PipelineContext<Object>) parentCtx;
            ctx.setCurrentData(asyncResult.getOutput());
        }

        // 触发完成回调
        if (completionHandler != null) {
            completionHandler.accept(parentCtx, asyncResult);
        }
    }
}