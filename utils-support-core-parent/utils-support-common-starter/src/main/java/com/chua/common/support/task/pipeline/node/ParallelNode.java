package com.chua.common.support.task.pipeline.node;

import com.chua.common.support.task.pipeline.core.ParallelErrorStrategy;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.exception.PipelineException;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 并行节点 — 多分支并行执行。
 *
 * <p>将多个 {@link Pipeline} 分支并行执行，所有分支共享父上下文的数据
 * （{@code attributes} 和 {@code currentData}），但各自维护独立的执行状态
 * （{@code currentNodeId}、{@code action}、{@code history} 等）。</p>
 *
 * <p><strong>数据共享模型：</strong></p>
 * <ul>
 *   <li>{@code attributes} Map — 同一引用，所有分支读写同一 Map（线程安全需调用方保证）</li>
 *   <li>{@code currentData} — 同一对象引用，修改对象本身对所有分支可见</li>
 *   <li>{@code originalData} — 只读共享</li>
 * </ul>
 *
 * <p><strong>错误处理策略：</strong></p>
 * <ul>
 *   <li>{@link ParallelErrorStrategy#WAIT_ALL} — 等待所有分支完成，汇总异常（默认）</li>
 *   <li>{@link ParallelErrorStrategy#FAIL_FAST} — 第一个失败时立即抛出，取消其他分支</li>
 * </ul>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * Pipeline branchA = PipelineBuilder.newBuilder("branchA")
 *     .task("a1", ctx -> { doA1(ctx); return null; }).taskEnd()
 *     .build();
 *
 * Pipeline branchB = PipelineBuilder.newBuilder("branchB")
 *     .task("b1", ctx -> { doB1(ctx); return null; }).taskEnd()
 *     .build();
 *
 * Pipeline pipeline = PipelineBuilder.newBuilder("parallel-demo")
 *     .task("init", ctx -> { init(ctx); return null; }).taskEnd()
 *     .parallel("parallel-group")
 *         .branch("a", branchA)
 *         .branch("b", branchB)
 *         .errorStrategy(ParallelErrorStrategy.WAIT_ALL)
 *     .taskEnd()
 *     .task("finalize", ctx -> { finalize(ctx); return null; }).taskEnd()
 *     .build();
 * }</pre>
 *
 * @author CH
 * @see ParallelErrorStrategy
 */
public class ParallelNode implements PipelineNode {

    /**
     * 节点唯一标识
     */
    private final String id;

    /**
     * 并行分支列表：分支名称 -> 子流水线
     */
    private final Map<String, Pipeline> branches;

    /**
     * 错误处理策略，默认 WAIT_ALL
     */
    private final ParallelErrorStrategy errorStrategy;

    /**
     * 节点参数映射（JSON 构建时传入，执行时注入到 ctx.nodeLocalData）
     */
    private Map<String, Object> params;

    /**
     * 构造并行节点。
     *
     * @param id            节点唯一标识
     * @param branches      分支名称 -> 子流水线映射
     * @param errorStrategy 错误处理策略，null 时默认 WAIT_ALL
     */
    public ParallelNode(String id, Map<String, Pipeline> branches, ParallelErrorStrategy errorStrategy) {
        this.id = id;
        this.branches = branches != null ? new LinkedHashMap<>(branches) : new LinkedHashMap<>();
        this.errorStrategy = errorStrategy != null ? errorStrategy : ParallelErrorStrategy.WAIT_ALL;
        this.params = Collections.emptyMap();
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
     * 获取并行分支映射。
     *
     * @return 分支名称 -> 子流水线的不可变映射
     */
    public Map<String, Pipeline> getBranches() {
        return Collections.unmodifiableMap(branches);
    }

    /**
     * 获取错误处理策略。
     *
     * @return 错误处理策略
     */
    public ParallelErrorStrategy getErrorStrategy() {
        return errorStrategy;
    }

    @Override
    public Map<String, Object> getParams() {
        return params;
    }

    /**
     * 设置节点参数映射。
     *
     * @param params 参数映射
     */
    public void setParams(Map<String, Object> params) {
        this.params = params != null ? params : Collections.emptyMap();
    }

    /**
     * 并行执行所有分支。
     *
     * <p>每个分支通过 {@link PipelineContext#createBranchContext()} 创建独立的上下文，
     * 共享 {@code attributes} 和 {@code currentData}（引用传递），但控制状态独立。</p>
     *
     * <p>所有分支执行完毕后，将各分支的执行上下文和历史存入父上下文的 attributes：</p>
     * <ul>
     *   <li>{@code parallel:{nodeId}:context:{branchName}} — 分支上下文</li>
     *   <li>{@code parallel:{nodeId}:history:{branchName}} — 分支执行历史</li>
     * </ul>
     *
     * @param context 父流水线上下文
     * @return null，按默认顺序继续执行下一节点
     */
    @Override
    public String execute(PipelineContext<?> context) {
        context.setCurrentNodeId(id);

        if (branches.isEmpty()) {
            return null;
        }

        // 单分支优化：直接同步执行，无需并行开销
        if (branches.size() == 1) {
            Map.Entry<String, Pipeline> entry = branches.entrySet().iterator().next();
            executeBranch(entry.getKey(), entry.getValue(), context);
            return null;
        }

        // 并行执行
        AtomicBoolean failed = new AtomicBoolean(false);
        ConcurrentLinkedQueue<BranchResult> results = new ConcurrentLinkedQueue<>();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map.Entry<String, Pipeline> entry : branches.entrySet()) {
            String branchName = entry.getKey();
            Pipeline branchPipeline = entry.getValue();

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                // FAIL_FAST: 已有分支失败，跳过此分支
                if (errorStrategy == ParallelErrorStrategy.FAIL_FAST && failed.get()) {
                    return;
                }

                PipelineContext<?> branchCtx = context.createBranchContext();
                try {
                    branchPipeline.execute(branchCtx);
                    results.add(new BranchResult(branchName, branchCtx, null));
                } catch (Exception e) {
                    failed.set(true);
                    results.add(new BranchResult(branchName, branchCtx, e));
                }
            });

            futures.add(future);
        }

        // 等待所有分支完成
        if (errorStrategy == ParallelErrorStrategy.FAIL_FAST) {
            // FAIL_FAST: 任一失败时立即传播异常
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            for (BranchResult result : results) {
                if (result.error != null) {
                    throw new PipelineException(
                            "Parallel branch '" + result.branchName + "' failed (FAIL_FAST)",
                            id, context.getPipelineId(), result.error);
                }
            }
        } else {
            // WAIT_ALL: 等待所有分支完成，汇总异常
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<Exception> errors = new ArrayList<>();
            for (BranchResult result : results) {
                if (result.error != null) {
                    errors.add(result.error);
                }
            }

            if (!errors.isEmpty()) {
                String errorBranches = results.stream()
                        .filter(r -> r.error != null)
                        .map(r -> r.branchName)
                        .collect(Collectors.joining(", "));
                throw new PipelineException(
                        "Parallel branches failed: [" + errorBranches + "] (WAIT_ALL)",
                        id, context.getPipelineId(), errors.get(0));
            }
        }

        // 将分支结果存入父上下文 attributes
        @SuppressWarnings("unchecked")
        PipelineContext<Object> parentCtx = (PipelineContext<Object>) context;
        for (BranchResult result : results) {
            parentCtx.setAttribute("parallel:" + id + ":context:" + result.branchName, result.context);
            parentCtx.setAttribute("parallel:" + id + ":history:" + result.branchName, result.context.getHistory());
        }

        return null;
    }

    /**
     * 同步执行单个分支（单分支优化路径）。
     *
     * @param branchName    分支名称
     * @param branchPipeline 分支流水线
     * @param parentCtx     父上下文
     */
    private void executeBranch(String branchName, Pipeline branchPipeline, PipelineContext<?> parentCtx) {
        PipelineContext<?> branchCtx = parentCtx.createBranchContext();
        branchPipeline.execute(branchCtx);

        @SuppressWarnings("unchecked")
        PipelineContext<Object> ctx = (PipelineContext<Object>) parentCtx;
        ctx.setAttribute("parallel:" + id + ":context:" + branchName, branchCtx);
        ctx.setAttribute("parallel:" + id + ":history:" + branchName, branchCtx.getHistory());
    }

    /**
     * 分支执行结果。
     */
    private static class BranchResult {
        final String branchName;
        final PipelineContext<?> context;
        final Exception error;

        BranchResult(String branchName, PipelineContext<?> context, Exception error) {
            this.branchName = branchName;
            this.context = context;
            this.error = error;
        }
    }
}