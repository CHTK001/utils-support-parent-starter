package com.chua.common.support.task.pipeline.node;

import com.chua.common.support.task.pipeline.core.ForkErrorStrategy;
import com.chua.common.support.task.pipeline.core.ForkResult;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.exception.PipelineException;

import java.util.*;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 分叉节点 — 多分支并行执行，阻塞等待所有分支完成。
 *
 * <p>将多个 {@link Pipeline} 分支并行执行，各分支通过 {@link PipelineContext#createBranchContext()}
 * 创建独立上下文，{@code currentData} 各分支独立（避免并发冲突），{@code nodeOutputs} 和
 * {@code attributes} 共享引用（方便跨分支/跨节点数据访问）。</p>
 *
 * <p><strong>与 ParallelNode（异步并行）的核心区别：</strong></p>
 * <ul>
 *   <li><strong>ForkNode</strong> — 分叉+阻塞，主干等待所有分支完成后才继续</li>
 *   <li><strong>ParallelNode</strong> — 并行+不阻塞，主干不等待，子流程在后台线程执行</li>
 * </ul>
 *
 * <p><strong>数据共享模型：</strong></p>
 * <ul>
 *   <li>{@code nodeOutputs} — 同一 ConcurrentHashMap 引用，各分支通过不同 key 写入各自结果</li>
 *   <li>{@code attributes} — 同一 Map 引用，所有分支读写同一 Map（线程安全需调用方保证）</li>
 *   <li>{@code currentData} — 各分支独立，修改互不影响（避免并发写入冲突）</li>
 *   <li>{@code originalData} — 只读共享</li>
 * </ul>
 *
 * <p><strong>分支结果存储：</strong></p>
 * <p>各分支执行完毕后，结果以 {@link ForkResult} 结构化对象存入父上下文的 {@code nodeOutputs}，
 * 键 为分叉节点的 节点id。后续节点可通过 {@code ctx.getData("fork1", ForkResult.class)}
 * 获取完整结果，再通过 {@link ForkResult#getBranch(String)} 获取指定分支的输出。</p>
 *
 * <pre>
 * nodeOutputs["fork1"] = ForkResult {
 *     nodeId: "fork1",
 *     branches: { "branchA": dataA, "branchB": dataB },
 *     histories: { "branchA": ["a1"], "branchB": ["b1"] }
 * }
 * </pre>
 *
 * <p><strong>错误处理策略：</strong></p>
 * <ul>
 *   <li>{@link ForkErrorStrategy#WAIT_ALL} — 等待所有分支完成，汇总异常（默认）</li>
 *   <li>{@link ForkErrorStrategy#FAIL_FAST} — 第一个失败时立即抛出，取消其他分支</li>
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
 * Pipeline pipeline = PipelineBuilder.newBuilder("fork-demo")
 *     .task("init", ctx -> { init(ctx); return null; }).taskEnd()
 *     .fork("fork-group")
 *         .branch("a", branchA)
 *         .branch("b", branchB)
 *         .errorStrategy(ForkErrorStrategy.WAIT_ALL)
 *     .taskEnd()
 *     .task("finalize", ctx -> { finalize(ctx); return null; }).taskEnd()
 *     .build();
 * }</pre>结束()
 * .构建();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see ForkErrorStrategy
 */
public class ForkNode implements PipelineNode {

    /**
     * 节点唯一标识
     */
    private final String id;

    /**
     * 分叉分支列表：分支名称 -> 子流水线
     */
    private final Map<String, Pipeline> branches;

    /**
     * 错误处理策略，默认 WAIT_全部
     */
    private final ForkErrorStrategy errorStrategy;

    /**
     * 节点参数映射（JSON 构建时传入，执行时注入到 ctx.节点本地数据）
     */
    private Map<String, Object> params;

    /**
     * 节点环境参数映射（定义时配置，运行时环境配置如模型路径、阈值等）
     */
    private Map<String, Object> env;

    /**
     * 前置处理器（在分叉分支执行前调用，可选）。
     *
     * <p>前置处理器在所有分叉分支启动之前执行，适用于初始化共享数据等场景。
     * 处理器返回值决定后续路由，返回 空 则继续执行分叉分支。</p>
     */
    private PipelineNode preHandler;

    /**
     * 构造分叉节点。
     *
     * @param id            节点唯一标识
     * @param branches      分支名称 -> 子流水线映射
     * @param errorStrategy 错误处理策略，空 时默认 WAIT_全部
     */
    public ForkNode(String id, Map<String, Pipeline> branches, ForkErrorStrategy errorStrategy) {
        this.id = id;
        this.branches = branches != null ? new LinkedHashMap<>(branches) : new LinkedHashMap<>();
        this.errorStrategy = errorStrategy != null ? errorStrategy : ForkErrorStrategy.WAIT_ALL;
        this.params = Collections.emptyMap();
    }

    /**
     * 获取节点 标识。
     *
     * @return 节点 标识
     */
    @Override
    public String getId() {
        return id;
    }

    /** 节点类型：fork。 */
    @Override
    public String getType() {
        return "fork";
    }

    /**
     * 获取分叉分支映射。
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
    public ForkErrorStrategy getErrorStrategy() {
        return errorStrategy;
    }

    /** 返回分支参数表。 */
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
     * 设置节点环境参数（定义时调用）。
     *
     * @param env 环境参数映射
     */
    public void setEnv(Map<String, Object> env) {
        this.env = env != null ? env : Collections.emptyMap();
    }

    /** 返回节点环境变量表。 */
    @Override
    public Map<String, Object> getEnv() {
        return env != null ? env : Collections.emptyMap();
    }

    /**
     * 设置前置处理器。
     *
     * <p>前置处理器在所有分叉分支启动之前执行，适用于初始化共享数据等场景。</p>
     *
     * @param preHandler 前置处理器
     * @return this
     */
    public ForkNode preHandler(PipelineNode preHandler) {
        this.preHandler = preHandler;
        return this;
    }

    /**
     * 获取前置处理器。
     *
     * @return 前置处理器，未设置时返回 空
     */
    public PipelineNode getPreHandler() {
        return preHandler;
    }

    /**
     * 并行执行所有分支。
     *
     * <p>每个分支通过 {@link PipelineContext#createBranchContext()} 创建独立的上下文，
     * 各分支的 {@code currentData} 独立（避免并发冲突），{@code nodeOutputs} 和
     * {@code attributes} 共享引用。</p>
     *
     * <p>所有分支执行完毕后，将结果以 {@link ForkResult} 结构化对象存入父上下文的
     * {@code nodeOutputs}，key 为本节点的 nodeId。结构化存储使 key 统一为 nodeId，
     * 调用方不需要知道节点类型即可获取结果。</p>
     *
     * @param context 父流水线上下文
     * @return null，按默认顺序继续执行下一节点
     */
    @Override
    public String execute(PipelineContext<?> context) {
        context.setCurrentNodeId(id);

        // 执行前置处理器（如果配置）
        if (preHandler != null) {
            String result = preHandler.execute(context);
            if (result != null) {
 // 前置处理器返回非空，跳过分叉执行，直接路由
                return result;
            }
        }

        if (branches.isEmpty()) {
            return null;
        }

        // 单分支优化：直接同步执行，无需并行开销
        if (branches.size() == 1) {
            Map.Entry<String, Pipeline> entry = branches.entrySet().iterator().next();
            executeBranch(entry.getKey(), entry.getValue(), context);
            return null;
        }

 // 并行执行（结构化并发：Structured Streaming Streaming任务scope — Java 25 API）
        ConcurrentLinkedQueue<BranchResult> results = new ConcurrentLinkedQueue<>();

        if (errorStrategy == ForkErrorStrategy.FAIL_FAST) {
 // 失败_FAST：任一分支失败时取消剩余分支
            AtomicBoolean failed = new AtomicBoolean(false);
            try (var scope = StructuredTaskScope.open(
                    StructuredTaskScope.Joiner.allUntil(subtask -> failed.get()))) {
                for (Map.Entry<String, Pipeline> entry : branches.entrySet()) {
                    String branchName = entry.getKey();
                    Pipeline branchPipeline = entry.getValue();
                    scope.fork(() -> {
                        PipelineContext<?> branchCtx = context.createBranchContext();
                        try {
                            branchPipeline.execute(branchCtx);
                            results.add(new BranchResult(branchName, branchCtx, null));
                        } catch (Exception e) {
                            results.add(new BranchResult(branchName, branchCtx, e));
                            // 信号失败，触发 scope 取消
                            failed.set(true);
                        }
                        return null;
                    });
                }
                // 等待所有分支完成（含被取消的）
                scope.join();

                // 检查失败分支
                for (BranchResult result : results) {
                    if (result.error != null) {
                        throw new PipelineException(
                                "Fork branch '" + result.branchName + "' failed (FAIL_FAST)",
                                id, context.getPipelineId(), result.error);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PipelineException("Fork execution interrupted",
                        id, context.getPipelineId(), e);
            }
        } else {
 // WAIT_全部：等待所有分支完成，汇总异常
            try (var scope = StructuredTaskScope.open(
                    StructuredTaskScope.Joiner.awaitAll())) {
                for (Map.Entry<String, Pipeline> entry : branches.entrySet()) {
                    String branchName = entry.getKey();
                    Pipeline branchPipeline = entry.getValue();
                    scope.fork(() -> {
                        PipelineContext<?> branchCtx = context.createBranchContext();
                        try {
                            branchPipeline.execute(branchCtx);
                            results.add(new BranchResult(branchName, branchCtx, null));
                        } catch (Exception e) {
                            results.add(new BranchResult(branchName, branchCtx, e));
                        }
                        return null;
                    });
                }
                // 等待所有分支完成
                scope.join();

                // 汇总异常
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
                            "Fork branches failed: [" + errorBranches + "] (WAIT_ALL)",
                            id, context.getPipelineId(), errors.getFirst());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PipelineException("Fork execution interrupted",
                        id, context.getPipelineId(), e);
            }
        }

 // 将分支结果以 fork结果 结构化对象存入父上下文 节点输出
        Map<String, Object> branchOutputs = new LinkedHashMap<>();
        Map<String, List<String>> branchHistories = new LinkedHashMap<>();
        for (BranchResult result : results) {
            branchOutputs.put(result.branchName, result.context.getCurrentData());
            branchHistories.put(result.branchName, result.context.getHistory());
        }
        ForkResult forkResult = new ForkResult(id, branchOutputs, branchHistories);
        context.setNodeOutput(id, forkResult);

        return null;
    }

    /**
     * 同步执行单个分支（单分支优化路径）。
     *
     * <p>结果同样以 {@link ForkResult} 结构化对象存入 {@code nodeOutputs}，
     * 与多分支并行路径保持一致的存储格式。</p>
     *
     * @param branchName    分支名称
     * @param branchPipeline 分支流水线
     * @param parentCtx     父上下文
     */
    private void executeBranch(String branchName, Pipeline branchPipeline, PipelineContext<?> parentCtx) {
        PipelineContext<?> branchCtx = parentCtx.createBranchContext();
        branchPipeline.execute(branchCtx);

 // 以 fork结果 结构化存储，与多分支路径格式一致
        Map<String, Object> branchOutputs = new LinkedHashMap<>();
        branchOutputs.put(branchName, branchCtx.getCurrentData());
        Map<String, List<String>> branchHistories = new LinkedHashMap<>();
        branchHistories.put(branchName, branchCtx.getHistory());
        ForkResult forkResult = new ForkResult(id, branchOutputs, branchHistories);
        parentCtx.setNodeOutput(id, forkResult);
    }

    /**
     * 分支执行结果。
     * @author CH
     * @since 4.0.0
     */
    private static class BranchResult {
        final String branchName; // 分支名称
        final PipelineContext<?> context; // 上下文
        final Exception error; // 错误

        BranchResult(String branchName, PipelineContext<?> context, Exception error) {
            this.branchName = branchName;
            this.context = context;
            this.error = error;
        }
    }
}
