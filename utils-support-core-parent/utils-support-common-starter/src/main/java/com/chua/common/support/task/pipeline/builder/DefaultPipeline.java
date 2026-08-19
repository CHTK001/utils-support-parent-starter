package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.callback.PipelineListener;
import com.chua.common.support.task.pipeline.core.Action;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.core.PipelineWal;
import com.chua.common.support.task.pipeline.core.RouteStrategy;
import com.chua.common.support.task.pipeline.exception.PipelineException;
import com.chua.common.support.task.pipeline.node.DecisionNode;
import com.chua.common.support.task.pipeline.node.EndNode;
import com.chua.common.support.task.pipeline.node.ForkNode;
import com.chua.common.support.task.pipeline.node.ParallelNode;
import com.chua.common.support.task.pipeline.node.SubPipelineNode;

import com.chua.common.support.task.retry.JdkRetryProvider;
import com.chua.common.support.task.retry.RetryConfig;
import com.chua.common.support.task.retry.RetryProvider;

import java.util.*;
import java.util.concurrent.StructuredTaskScope;
import java.util.stream.Collectors;

/**
 * 默认流水线实现。
 *
 * <p>流水线引擎的核心实现，负责节点调度、动作处理、循环检测、回调触发和 B+ 树打印。</p>
 *
 * <p><strong>执行流程：</strong></p>
 * <ol>
 *   <li>从起始节点开始，根据 {@code nextNodeId} 依次执行节点</li>
 *   <li>每个节点执行前触发 {@link PipelineListener#beforeNode}</li>
 *   <li>节点执行后根据 {@link Action} 决定下一步：NEXT 按序前进、PREV 回退、JUMP 跳转等</li>
 *   <li>节点未指定 nextNodeId 时，按添加顺序自动前进到下一节点</li>
 *   <li>循环检测：节点重复执行时抛出 {@link PipelineException}（REPLAY 动作除外）</li>
 *   <li>执行 {@link EndNode} 或到达终止节点 ID 时结束</li>
 *   <li>节点异常时：异常存入 ctx.lastError → 触发 onError 回调 → 若返回恢复节点 ID 则路由继续执行，否则终止</li>
 * </ol>
 *
 * <p><strong>节点输出存储契约（{@code nodeOutputs}）：</strong></p>
 * <ol>
 *   <li><strong>null 输出跳过</strong> — 节点执行后 {@code currentData} 为 null 时不再写入
 *       {@code nodeOutputs}（该 Map 为 ConcurrentHashMap，写入 null 值会抛 NPE；
 *       下游节点对缺失 key 读取到 null，语义比旧版直接崩溃更明确）</li>
 *   <li><strong>结构化结果不覆盖</strong> — 节点已自行存储结构化结果
 *       （{@code AsyncResult}/{@code ForkResult}/{@code SubPipelineResult}，key 为本节点 nodeId）时，
 *       引擎不再用 {@code currentData} 覆盖，保证 {@code ctx.getData(nodeId, XxxResult.class)} 可取回完整结果</li>
 * </ol>
 *
 * <p><strong>恢复执行语义（{@code resume}）：</strong></p>
 * <ul>
 *   <li>{@link #resume(PipelineContext)} 与 {@link #execute(PipelineContext)} 一致：
 *       上下文未指定 {@code nextNodeId}（如新建的上下文）时自动回退到流水线起始节点，避免空转</li>
 *   <li>WAIT 挂起恢复不受影响：挂起前引擎已将 {@code nextNodeId} 推进到下一节点，恢复时从断点继续</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultPipeline implements Pipeline {

    /**
     * 内部属性键 — StructuredTaskScope 实例，通过 PipelineContext.attributes 传递给异步节点。
     *
     * <p>异步子流水线节点（{@link ParallelNode}）通过此键获取 Pipeline 级别的
     * StructuredTaskScope，将异步任务 fork 进去，确保 Pipeline 返回前所有异步工作完成。</p>
     *
     * <p><strong>生命周期：</strong>
     * executeWith() 创建 scope → 存入 attributes → async 节点 fork → run() 完成 → join + close</p>
     */
    static final String ATTR_PIPELINE_SCOPE = "__pipelineScope__";

    /**
     * 流水线唯一标识
     */
    private final String id;

    /**
     * 起始节点 ID
     */
    private final String startNodeId;

    /**
     * 终止节点 ID
     */
    private final String endNodeId;

    /**
     * 节点 ID -&gt; 节点实例的映射
     */
    private final Map<String, PipelineNode> nodeMap;

    /**
     * 有序节点列表，用于确定默认执行顺序
     */
    private final List<PipelineNode> orderedNodes;

    /**
     * 全局回调监听器列表
     */
    private final List<PipelineListener> listeners;

    /**
     * 节点拓扑边集合，用于 B+ 树打印
     */
    private final Map<String, List<Edge>> flowTree;

    /**
     * 被判断节点分支指向的目标节点 ID 集合，构建树时跳过这些节点的默认边
     */
    private final Set<String> decisionTargets;

    /**
     * 路由策略：当目标节点不存在时的处理方式
     */
    private final RouteStrategy routeStrategy;

    /**
     * WAL 持久化实例，null 表示未启用 WAL
     */
    private PipelineWal pipelineWal;

    /**
     * 构造默认流水线。
     *
     * @param id            流水线 ID
     * @param startNodeId   起始节点 ID
     * @param endNodeId     终止节点 ID
     * @param nodeMap       节点 ID 映射
     * @param orderedNodes  有序节点列表
     * @param listeners     全局回调监听器
     * @param routeStrategy 路由策略：当目标节点不存在时的处理方式
     */
    public DefaultPipeline(String id, String startNodeId, String endNodeId,
                           Map<String, PipelineNode> nodeMap,
                           List<PipelineNode> orderedNodes,
                           List<PipelineListener> listeners,
                           RouteStrategy routeStrategy) {
        this(id, startNodeId, endNodeId, nodeMap, orderedNodes, listeners, routeStrategy, null);
    }

    /**
     * 构造默认流水线（带 WAL 持久化）。
     *
     * @param id            流水线 ID
     * @param startNodeId   起始节点 ID
     * @param endNodeId     终止节点 ID
     * @param nodeMap       节点 ID 映射
     * @param orderedNodes  有序节点列表
     * @param listeners     全局回调监听器
     * @param routeStrategy 路由策略
     * @param pipelineWal   WAL 持久化实例，null 表示不启用 WAL
     */
    public DefaultPipeline(String id, String startNodeId, String endNodeId,
                           Map<String, PipelineNode> nodeMap,
                           List<PipelineNode> orderedNodes,
                           List<PipelineListener> listeners,
                           RouteStrategy routeStrategy,
                           PipelineWal pipelineWal) {
        this.id = id;
        this.startNodeId = startNodeId;
        this.endNodeId = endNodeId;
        this.nodeMap = nodeMap;
        this.orderedNodes = orderedNodes;
        this.listeners = listeners;
        this.routeStrategy = routeStrategy != null ? routeStrategy : RouteStrategy.THROW;
        this.pipelineWal = pipelineWal;
        this.decisionTargets = new HashSet<>();
        this.flowTree = buildFlowTree();
    }

    @Override
    /** 获取Id */
    public String getId() {
        return id;
    }

    @Override
    /** 执行 */
    public <T> PipelineContext<T> execute(T input) {
        PipelineContext<T> ctx = new PipelineContext<>(id, input);
        if (startNodeId != null) {
            ctx.setNextNodeId(startNodeId);
        }
        // WAL：记录启动事件
        if (pipelineWal != null) {
            try {
                pipelineWal.open();
                pipelineWal.appendStart(input);
            } catch (Exception e) {
                // WAL 启动失败不影响流水线执行
            }
        }
        return executeWith(ctx);
    }

    /**
     * 使用已有上下文执行流水线。
     *
     * <p>若上下文未指定起始节点（{@code nextNodeId == null}，如并行分支上下文），
     * 自动设置为流水线的起始节点后执行。</p>
     *
     * @param existingContext 已存在的上下文实例
     * @param <T>             数据类型
     * @return 执行完成后的上下文
     */
    @Override
    public <T> PipelineContext<T> execute(PipelineContext<T> existingContext) {
        // 若上下文未指定起始节点（如并行分支上下文），自动设置为流水线的起始节点
        if (existingContext.getNextNodeId() == null && startNodeId != null) {
            existingContext.setNextNodeId(startNodeId);
        }
        return executeWith(existingContext);
    }

    /**
     * 恢复执行流水线。
     *
     * <p>与 {@link #execute(PipelineContext)} 语义一致：上下文未指定起始节点时
     * （{@code nextNodeId == null}，如新建的上下文），自动设置为流水线起始节点后执行，避免空转。</p>
     *
     * <p><strong>典型场景：</strong></p>
     * <ul>
     *   <li>WAIT 挂起恢复 — 挂起前引擎已将 {@code nextNodeId} 推进到下一节点，从断点继续执行</li>
     *   <li>新建上下文直接 resume — 自动回退到起始节点从头执行（与 {@link #execute(PipelineContext)} 等价）</li>
     * </ul>
     *
     * @param ctx 上下文实例
     * @param <T> 数据类型
     * @return 执行完成后的上下文
     */
    @Override
    public <T> PipelineContext<T> resume(PipelineContext<T> ctx) {
        // 与 execute(PipelineContext) 一致：上下文未指定起始节点时自动设置为流水线起始节点
        if (ctx.getNextNodeId() == null && startNodeId != null) {
            ctx.setNextNodeId(startNodeId);
        }
        return executeWith(ctx);
    }

    @Override
    /** 恢复 */
    public <T> PipelineContext<T> resume(T input) {
        // WAL 恢复：尝试从 WAL 回放恢复上下文
        if (pipelineWal != null) {
            try {
                pipelineWal.open();
                PipelineContext<T> restored = pipelineWal.replay(input);
                if (restored != null) {
                    // 恢复成功：从断点继续执行
                    // 设置动作为 NEXT，从 nextNodeId 继续执行
                    restored.setAction(Action.NEXT);
                    return executeWith(restored);
                }
                // 无 WAL 数据：等同 execute
            } catch (Exception e) {
                // WAL 恢复失败，回退到普通执行
            }
        }
        // 无 WAL 或恢复失败：等同 execute
        return execute(input);
    }

    @Override
    /** 停止 */
    public void stop() {
        // 终止执行 + 销毁 WAL
        if (pipelineWal != null) {
            pipelineWal.destroy();
            pipelineWal = null;
        }
    }

    /**
     * 使用已有上下文执行流水线。
     *
     * <p>复用传入的上下文实例，不重建上下文对象，
     * 首次执行时需由调用方设置起始节点 ID。</p>
     *
     * <p><strong>结构化并发保证：</strong>
     * 创建 Pipeline 级别的 StructuredTaskScope，通过 {@code ctx.attributes} 传递给异步节点。
     * 异步子流水线节点（{@link ParallelNode}）将异步任务 fork 进此 scope，
     * Pipeline 返回前 join 等待所有异步工作完成，确保结构化并发语义。</p>
     *
     * @param ctx 已存在的上下文实例
     * @param <T> 数据类型
     * @return 执行完成后的上下文
     */
    public <T> PipelineContext<T> executeWith(PipelineContext<T> ctx) {
        ctx.setAction(Action.NEXT);

        // 检查是否已有 Pipeline 级 scope（嵌套 Pipeline 场景：子流水线继承父 scope）
        @SuppressWarnings("unchecked")
        StructuredTaskScope<Object, Void> existingScope =
                (StructuredTaskScope<Object, Void>) ctx.getAttributes().get(ATTR_PIPELINE_SCOPE);

        if (existingScope != null) {
            // 嵌套场景：复用父 Pipeline 的 scope，不创建新的
            run(ctx);
            return ctx;
        }

        // 顶层 Pipeline：创建 scope，确保所有 fork 的异步任务在返回前完成
        try (var scope = StructuredTaskScope.open()) {
            ctx.setAttribute(ATTR_PIPELINE_SCOPE, scope);
            run(ctx);
            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("Pipeline execution interrupted",
                    "pipeline", ctx.getPipelineId(), e);
        } finally {
            ctx.getAttributes().remove(ATTR_PIPELINE_SCOPE);
            // WAL：正常完成时关闭（保留 WAL 文件用于审计）
            if (pipelineWal != null) {
                try {
                    pipelineWal.markCheckpoint();
                } catch (Exception ignored) {
                    // checkpoint 失败不影响返回
                }
                pipelineWal.close();
            }
        }

        return ctx;
    }

    /**
     * 单次 execute/resume 的最大节点执行次数，防止无限循环
     */
    private static final int MAX_EXECUTION_DEPTH = 1000;

    /**
     * 流水线核心执行循环。
     *
     * <p>每执行完一个节点，引擎自动将节点输出存入 {@code nodeOutputs}，
     * 存储遵循类级 Javadoc 中定义的<strong>节点输出存储契约</strong>：
     * {@code currentData} 为 null 时跳过存储；节点已自存结构化结果时不覆盖。</p>
     *
     * @param ctx 流水线上下文
     * @param <T> 数据类型
     */
    @SuppressWarnings("unchecked")
    private <T> void run(PipelineContext<T> ctx) {
        int depth = 0;
        try {
            // 触发启动回调（仅一次）
            fireOnStart(ctx);

            while (ctx.getAction() != Action.EXIT) {
                if (++depth > MAX_EXECUTION_DEPTH) {
                    throw new PipelineException("Execution depth exceeded " + MAX_EXECUTION_DEPTH + ", possible infinite loop",
                            ctx.getCurrentNodeId(), id, null);
                }
                String nodeId = ctx.getNextNodeId();
                if (nodeId == null) {
                    break;
                }

                PipelineNode node = nodeMap.get(nodeId);
                if (node == null) {
                    // 检查点1：主循环入口 — 目标节点不存在
                    String nextInOrder = getNextNodeIdInOrder(nodeId);
                    switch (routeStrategy) {
                        case EXIT:
                            ctx.setAction(Action.EXIT);
                            break;
                        case NEXT:
                            ctx.setNextNodeId(nextInOrder);
                            ctx.setAction(Action.NEXT);
                            continue;
                        default:
                            throw new PipelineException("Node not found: " + nodeId
                                            + ". Available nodes: " + nodeMap.keySet(),
                                    nodeId, id, null);
                    }
                    break;
                }

                if (ctx.getAction() != Action.REPLAY && ctx.getHistory().contains(nodeId)) {
                    throw new PipelineException("Cycle detected, node already executed: " + nodeId,
                            nodeId, id, null);
                }

                if (ctx.getAction() == Action.REPLAY) {
                    // 重播不删除历史记录：循环检测已由 action==REPLAY 跳过，历史应保留每次执行
                    ctx.setAction(Action.NEXT);
                }

                ctx.setCurrentNodeId(nodeId);
                // 注入按顺序的下一个节点 ID（只读，供节点判断逻辑使用）
                ctx.setNextNodeIdInOrder(getNextNodeIdInOrder(nodeId));
                // 清空节点本地数据（节点间隔离）
                ctx.clearNodeLocalData();
                // 注入节点参数到 nodeLocalData（JSON 构建时的 params 字段）
                if (node.getParams() != null && !node.getParams().isEmpty()) {
                    ctx.getNodeLocalData().putAll(node.getParams());
                }
                // 注入节点环境参数到 nodeLocalData（以 "env." 前缀隔离）
                if (node.getEnv() != null && !node.getEnv().isEmpty()) {
                    Map<String, Object> localData = ctx.getNodeLocalData();
                    for (Map.Entry<String, Object> entry : node.getEnv().entrySet()) {
                        localData.put("env." + entry.getKey(), entry.getValue());
                    }
                }
                fireBeforeNode(ctx);
                String prevNextId = ctx.getNextNodeId();

                // 校验数据依赖：检查 unit 声明的依赖节点输出是否已存在
                Set<String> units = node.getUnits();
                if (units != null && !units.isEmpty()) {
                    for (String unitId : units) {
                        if (!ctx.getNodeOutputs().containsKey(unitId)) {
                            throw new PipelineException(
                                    "Unit dependency not satisfied: node '" + nodeId
                                            + "' requires output from '" + unitId
                                            + "', but it has not been produced yet",
                                    nodeId, id, null);
                        }
                    }
                    // 将依赖数据注入 nodeLocalData，方便节点通过 getNodeLocalValue("unit:xxx") 获取
                    Map<String, Object> localData = ctx.getNodeLocalData();
                    for (String unitId : units) {
                        localData.put("unit:" + unitId, ctx.getNodeOutputs().get(unitId));
                    }
                }

                try {
                    // 检查重试配置：有则通过 RetryProvider 执行，无则直接执行
                    String result;
                    RetryConfig retryConfig = node.getRetryConfig();
                    if (retryConfig != null && retryConfig.getMaxRetries() > 0) {
                        result = RETRY_PROVIDER.execute(() -> node.execute(ctx), retryConfig);
                    } else {
                        result = node.execute(ctx);
                    }
                    // 处理 execute() 返回值：仅当节点未显式设置其他动作时，返回值才触发 JUMP
                    // 优先级：显式动作（EXIT/WAIT/BREAK/REPLAY/PREV）> 返回值 > 默认 NEXT
                    if (result != null && !result.isEmpty() && ctx.getAction() == Action.NEXT) {
                        // 检查点3：execute()返回值目标不存在
                        if (!nodeMap.containsKey(result)) {
                            switch (routeStrategy) {
                                case EXIT:
                                    ctx.setAction(Action.EXIT);
                                    break;
                                case NEXT:
                                    ctx.setNextNodeId(getNextNodeIdInOrder(nodeId));
                                    ctx.setAction(Action.NEXT);
                                    break;
                                default:
                                    throw new PipelineException("Execute result target node not found: " + result
                                            + " (returned from node: " + nodeId + ")"
                                            + ". Available nodes: " + nodeMap.keySet(),
                                            nodeId, id, null);
                            }
                        } else {
                            ctx.setNextNodeId(result);
                            ctx.setAction(Action.JUMP);
                        }
                    }
                } catch (Exception e) {
                    // 将异常存入上下文，供错误恢复节点判断
                    ctx.setLastError(e);
                    // 触发 onError 回调，获取恢复节点 ID
                    String recoveryNodeId = fireOnError(ctx, e);
                    if (recoveryNodeId != null && !recoveryNodeId.isEmpty()) {
                        // 验证恢复节点是否存在
                        if (!nodeMap.containsKey(recoveryNodeId)) {
                            throw new PipelineException(
                                    "Recovery node not found: " + recoveryNodeId
                                            + " (error from node: " + nodeId + ")",
                                    nodeId, id, e);
                        }
                        // 路由到恢复节点继续执行
                        ctx.setNextNodeId(recoveryNodeId);
                        ctx.setAction(Action.NEXT);
                        ctx.addHistory(nodeId);
                        fireAfterNode(ctx);
                        fireOnDraw(ctx);
                        continue;
                    }
                    // 无恢复节点，终止流水线
                    throw new PipelineException("Node execution failed: " + nodeId, nodeId, id, e);
                }

                ctx.addHistory(nodeId);
                // 自动存储节点输出到 nodeOutputs，方便后续节点跨节点访问（存储契约见类级 Javadoc）：
                // 1. currentData 为 null 时不存储 —— nodeOutputs 为 ConcurrentHashMap，null 值会抛 NPE；
                //    旧版直接崩溃，新版下游对缺失 key 读取到 null，行为更明确
                // 2. 节点已自行存储结构化结果（AsyncResult/ForkResult/SubPipelineResult）时不覆盖 ——
                //    旧版用 currentData 覆盖导致 ClassCastException，新版保留节点自存的结构化结果
                if (ctx.getCurrentData() != null && !ctx.getNodeOutputs().containsKey(nodeId)) {
                    ctx.setNodeOutput(nodeId, ctx.getCurrentData());
                }
                // WAL：记录节点完成事件
                if (pipelineWal != null) {
                    try {
                        pipelineWal.appendNodeComplete(ctx);
                    } catch (Exception ignored) {
                        // WAL 记录失败不影响流水线执行
                    }
                }
                fireAfterNode(ctx);
                fireOnDraw(ctx);
                if (ctx.getAction() == Action.WAIT) {
                    if (ctx.getAction() == Action.WAIT
                            && Objects.equals(prevNextId, ctx.getNextNodeId())) {
                        // 挂起前推进到下一节点，resume 时从下一节点继续执行
                        ctx.setNextNodeId(getNextNodeIdInOrder(nodeId));
                    }
                    break;
                }

                if (ctx.getAction() == Action.REPLAY) {
                    ctx.setNextNodeId(ctx.getCurrentNodeId());
                } else if (ctx.getAction() == Action.PREV) {
                    ctx.setAction(Action.NEXT);
                    List<String> history = ctx.getHistory();
                    String currId = ctx.getCurrentNodeId();
                    history.remove(currId);
                    if (history.size() >= 1) {
                        String prevId = history.remove(history.size() - 1);
                        ctx.setNextNodeId(prevId);
                    } else {
                        ctx.setNextNodeId(null);
                    }
                } else if (ctx.getAction() == Action.JUMP) {
                    // 验证 JUMP 目标节点是否存在
                    String jumpTarget = ctx.getNextNodeId();
                    if (jumpTarget != null && !nodeMap.containsKey(jumpTarget)) {
                        // 检查点2：JUMP目标节点不存在
                        switch (routeStrategy) {
                            case EXIT:
                                ctx.setAction(Action.EXIT);
                                break;
                            case NEXT:
                                ctx.setNextNodeId(getNextNodeIdInOrder(nodeId));
                                ctx.setAction(Action.NEXT);
                                break;
                            default:
                                throw new PipelineException("Route target node not found: " + jumpTarget
                                        + " (routed from node: " + nodeId + ")"
                                        + ". Available nodes: " + nodeMap.keySet(),
                                        nodeId, id, null);
                        }
                    } else {
                        ctx.setAction(Action.NEXT);
                    }
                } else if (ctx.getAction() == Action.BREAK) {
                    // 中断当前分支：跳到按顺序的下一个节点继续执行
                    ctx.setNextNodeId(getNextNodeIdInOrder(nodeId));
                    ctx.setAction(Action.NEXT);
                } else if (Action.NEXT.equals(ctx.getAction()) && Objects.equals(prevNextId, ctx.getNextNodeId())) {
                    // 节点未修改 nextNodeId 且动作为 NEXT，按默认顺序前进
                    ctx.setNextNodeId(getNextNodeIdInOrder(nodeId));
                }

                if (endNodeId != null && nodeId.equals(endNodeId)) {
                    ctx.setAction(Action.EXIT);
                    break;
                }
            }

            fireOnComplete(ctx);
        } catch (PipelineException e) {
            throw e;
        } catch (Exception e) {
            fireOnError(ctx, e);
            throw new PipelineException("Pipeline execution failed", ctx.getCurrentNodeId(), id, e);
        }
    }

    /**
     * 在有序节点列表中查找当前节点的下一个节点。
     *
     * @param currentNodeId 当前节点 ID
     * @return 下一节点 ID，不存在时返回 null
     */
    private String getNextNodeIdInOrder(String currentNodeId) {
        if (orderedNodes == null) {
            return null;
        }
        boolean found = false;
        for (PipelineNode node : orderedNodes) {
            String nid = nodeId(node);
            if (found) {
                return nid;
            }
            if (Objects.equals(nid, currentNodeId)) {
                found = true;
            }
        }
        return null;
    }

    // ==================== ANSI 颜色支持 ====================

    /** ANSI 重置 */
    private static final String ANSI_RESET = "\u001B[0m";
    /** ANSI 粗体 */
    private static final String ANSI_BOLD = "\u001B[1m";
    /** ANSI 暗色（降低亮度） */
    private static final String ANSI_DIM = "\u001B[2m";
    /** ANSI 绿色 — Task 节点 */
    private static final String ANSI_GREEN = "\u001B[32m";
    /** ANSI 黄色 — Decision 节点 */
    private static final String ANSI_YELLOW = "\u001B[33m";
    /** ANSI 蓝色 — SubPipeline 节点 */
    private static final String ANSI_BLUE = "\u001B[34m";
    /** ANSI 青色 — Fork 节点 */
    private static final String ANSI_CYAN = "\u001B[36m";
    /** ANSI 红色 — 错误标记 */
    private static final String ANSI_RED = "\u001B[31m";

    /** 重试提供者 — 用于节点级重试执行 */
    private static final RetryProvider RETRY_PROVIDER = new JdkRetryProvider();

    /** 节点类型图标：Task */
    private static final String ICON_TASK = "●";
    /** 节点类型图标：Decision */
    private static final String ICON_DECISION = "◆";
    /** 节点类型图标：SubPipeline */
    private static final String ICON_SUB = "▶";
    /** 节点类型图标：Fork */
    private static final String ICON_FORK = "⋈";
    /** 节点类型图标：End */
    private static final String ICON_END = "◉";
    /** 执行状态标记：已执行 */
    private static final String MARK_EXECUTED = "✓";
    /** 执行状态标记：未执行 */
    private static final String MARK_PENDING = "○";
    /** 执行状态标记：错误 */
    private static final String MARK_ERROR = "✗";

    /**
     * 应用 ANSI 颜色。
     *
     * @param text     原始文本
     * @param colorCode ANSI 颜色码
     * @return 带颜色标记的文本（颜色禁用时返回原文本）
     */
    private static String colorize(String text, String colorCode, boolean colorEnabled) {
        if (!colorEnabled) {
            return text;
        }
        return colorCode + text + ANSI_RESET;
    }

    /**
     * 根据节点类型获取图标。
     *
     * @param node 节点实例
     * @return 类型图标
     */
    private static String nodeIcon(PipelineNode node) {
        if (node instanceof DecisionNode) {
            return ICON_DECISION;
        } else if (node instanceof SubPipelineNode) {
            return ICON_SUB;
        } else if (node instanceof ForkNode) {
            return ICON_FORK;
        } else if (node instanceof EndNode) {
            return ICON_END;
        }
        return ICON_TASK;
    }

    /**
     * 根据节点类型获取 ANSI 颜色码。
     *
     * @param node 节点实例
     * @return ANSI 颜色码
     */
    private static String nodeColor(PipelineNode node) {
        if (node instanceof DecisionNode) {
            return ANSI_YELLOW;
        } else if (node instanceof SubPipelineNode) {
            return ANSI_BLUE;
        } else if (node instanceof ForkNode) {
            return ANSI_CYAN;
        } else if (node instanceof EndNode) {
            return ANSI_DIM;
        }
        return ANSI_GREEN;
    }

    /**
     * 获取节点 ID。
     *
     * @param node 节点实例
     * @return 节点 ID
     */
    static String nodeId(PipelineNode node) {
        String id = node.getId();
        return id != null ? id : node.getClass().getSimpleName();
    }

    /**
     * 拓扑边，表示节点间的连接关系。
     */
    static class Edge {

        /**
         * 源节点 ID
         */
        final String from;

        /**
         * 目标节点 ID
         */
        final String to;

        /**
         * 边标签，如 "true"、"false"、"sub"
         */
        final String label;

        Edge(String from, String to, String label) {
            this.from = from;
            this.to = to;
            this.label = label;
        }
    }

    /**
     * 构建节点拓扑边集合，用于 B+ 树打印。
     *
     * @return 节点 ID -&gt; 出边列表的映射
     */
    private Map<String, List<Edge>> buildFlowTree() {
        Map<String, List<Edge>> tree = new LinkedHashMap<>();

        decisionTargets.clear();
        for (PipelineNode node : orderedNodes) {
            if (node instanceof DecisionNode) {
                DecisionNode dn = (DecisionNode) node;
                dn.getBranches().values().forEach(decisionTargets::add);
            }
        }

        if (orderedNodes == null) {
            return tree;
        }
        for (int i = 0; i < orderedNodes.size(); i++) {
            PipelineNode node = orderedNodes.get(i);
            String nid = nodeId(node);
            String defaultNext = (i + 1 < orderedNodes.size()) ? nodeId(orderedNodes.get(i + 1)) : null;

            if (node instanceof DecisionNode) {
                DecisionNode dn = (DecisionNode) node;
                dn.getBranches().forEach((label, nextId) ->
                    tree.computeIfAbsent(nid, k -> new ArrayList<>())
                        .add(new Edge(nid, nextId, label)));
            } else if (node instanceof EndNode) {
                continue;
            } else if (node instanceof SubPipelineNode) {
                SubPipelineNode sn = (SubPipelineNode) node;
                String subStart = sn.getSubPipelineStartId();
                tree.computeIfAbsent(nid, k -> new ArrayList<>())
                    .add(new Edge(nid, subStart, "sub"));
                if (defaultNext != null) {
                    String subEnd = sn.getSubPipelineEndId();
                    tree.computeIfAbsent(subEnd, k -> new ArrayList<>())
                        .add(new Edge(subEnd, defaultNext, ""));
                }
            } else if (node instanceof ForkNode) {
                ForkNode pn = (ForkNode) node;
                for (Map.Entry<String, Pipeline> entry : pn.getBranches().entrySet()) {
                    String branchName = entry.getKey();
                    String branchStartId = "fork:" + nid + ":" + branchName + ":start";
                    tree.computeIfAbsent(nid, k -> new ArrayList<>())
                        .add(new Edge(nid, branchStartId, "branch:" + branchName));
                }
                if (defaultNext != null) {
                    String forkEndId = "fork:" + nid + ":end";
                    tree.computeIfAbsent(forkEndId, k -> new ArrayList<>())
                        .add(new Edge(forkEndId, defaultNext, ""));
                }
            } else {
                if (defaultNext != null && !decisionTargets.contains(nid)) {
                    tree.computeIfAbsent(nid, k -> new ArrayList<>())
                        .add(new Edge(nid, defaultNext, ""));
                }
            }

            if (endNodeId != null && Objects.equals(nid, endNodeId)) {
                tree.computeIfAbsent(nid, k -> new ArrayList<>());
            }
        }
        return tree;
    }

    /** lastTreeLineCount */
    private transient int lastTreeLineCount = 0;
    /** treeLineCounter */
    private transient int treeLineCounter = 0;
    /** countingTreeLines */
    private transient boolean countingTreeLines = false;

    @Override
    /** PrintTree */
    public void printTree(List<String> history) {
        printTree(history, false);
    }

    @Override
    /** PrintTree */
    public void printTree(List<String> history, boolean colorEnabled) {
        Set<String> executed = history != null ? new HashSet<>(history) : Collections.emptySet();
        String pipelineLabel = colorEnabled ? colorize(id, ANSI_BOLD + ANSI_CYAN, true) : id;
        treePrintln("Pipeline: " + pipelineLabel);
        printNodeTree(startNodeId, "", true, executed, colorEnabled);
    }

    /**
     * 递归打印节点树（带颜色和图标支持，递归展开子流水线和并行分支）。
     *
     * <p>遵循"自己管自己"原则：</p>
     * <ul>
     *   <li>SubPipelineNode — 调用子流水线的 printNodeTree 递归展开内部节点</li>
     *   <li>ForkNode — 调用每个分支流水线的 printNodeTree 递归展开分支内部节点</li>
     * </ul>
     *
     * @param nodeId       当前节点 ID
     * @param prefix       行前缀
     * @param isLast       是否为同级最后一个节点
     * @param executed     已执行节点 ID 集合
     * @param colorEnabled 是否启用 ANSI 颜色
     */
    private void printNodeTree(String nodeId, String prefix, boolean isLast, Set<String> executed, boolean colorEnabled) {
        if (nodeId == null) {
            return;
        }

        PipelineNode node = nodeMap.get(nodeId);
        String connector = isLast ? "└── " : "├── ";

        // 构建节点显示行：图标 + nodeId + 状态标记
        String icon = node != null ? nodeIcon(node) : ICON_TASK;
        String statusMark;
        if (executed.contains(nodeId)) {
            statusMark = colorEnabled ? colorize(MARK_EXECUTED, ANSI_GREEN, true) : MARK_EXECUTED;
        } else {
            statusMark = colorEnabled ? colorize(MARK_PENDING, ANSI_DIM, true) : "";
        }

        String nodeLabel;
        if (node != null && colorEnabled) {
            nodeLabel = colorize(icon + " " + nodeId, nodeColor(node), true);
        } else if (node != null) {
            nodeLabel = icon + " " + nodeId;
        } else {
            nodeLabel = nodeId;
        }

        treePrintln(prefix + connector + nodeLabel + (statusMark.isEmpty() ? "" : " " + statusMark));

        // 处理子流水线节点 — 递归展开子流水线内部树
        if (node instanceof SubPipelineNode) {
            SubPipelineNode sn = (SubPipelineNode) node;
            String childPrefix = prefix + (isLast ? "    " : "│   ");
            Pipeline subPipeline = sn.getSubPipeline();
            if (subPipeline instanceof DefaultPipeline) {
                ((DefaultPipeline) subPipeline).printNodeTree(
                        ((DefaultPipeline) subPipeline).startNodeId,
                        childPrefix, true, executed, colorEnabled);
            }
            return;
        }

        // 处理并行节点 — 递归展开每个分支的内部树
        if (node instanceof ForkNode) {
            ForkNode pn = (ForkNode) node;
            String childPrefix = prefix + (isLast ? "    " : "│   ");
            Map<String, Pipeline> branches = pn.getBranches();
            int branchIndex = 0;
            for (Map.Entry<String, Pipeline> entry : branches.entrySet()) {
                String branchName = entry.getKey();
                Pipeline branchPipeline = entry.getValue();
                boolean lastBranch = (branchIndex == branches.size() - 1);

                String branchConn = lastBranch ? "└── " : "├── ";
                String branchLabel = colorEnabled
                        ? colorize("[branch] " + branchName, ANSI_CYAN, true)
                        : "[branch] " + branchName;
                treePrintln(childPrefix + branchConn + branchLabel);

                String branchChildPrefix = childPrefix + (lastBranch ? "    " : "│   ");
                if (branchPipeline instanceof DefaultPipeline) {
                    ((DefaultPipeline) branchPipeline).printNodeTree(
                            ((DefaultPipeline) branchPipeline).startNodeId,
                            branchChildPrefix, true, executed, colorEnabled);
                }
                branchIndex++;
            }
            return;
        }

        // 普通节点 — 沿 flowTree 边递归打印子节点
        List<Edge> edges = flowTree.get(nodeId);
        if (edges == null || edges.isEmpty()) {
            return;
        }

        String childPrefix = prefix + (isLast ? "    " : "│   ");
        for (int i = 0; i < edges.size(); i++) {
            Edge edge = edges.get(i);
            boolean lastEdge = (i == edges.size() - 1);
            printNodeTree(edge.to, childPrefix, lastEdge, executed, colorEnabled);
        }
    }

    @Override
    /** ToString */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Pipeline{id='").append(id).append("', nodes=[");
        sb.append(orderedNodes.stream().map(n -> {
            String nid = nodeId(n);
            List<Edge> edges = flowTree.get(nid);
            if (edges != null && !edges.isEmpty()) {
                return nid + " -> " + edges.stream()
                    .map(e -> e.to + (e.label.isEmpty() ? "" : "[" + e.label + "]"))
                    .collect(Collectors.joining(", "));
            }
            return nid;
        }).collect(Collectors.joining(", ")));
        sb.append("]}");
        return sb.toString();
    }

    /** FireBeforeNode */
    private void fireBeforeNode(PipelineContext<?> ctx) {
        for (PipelineListener listener : listeners) {
            listener.beforeNode(ctx);
        }
    }

    /** FireAfterNode */
    private void fireAfterNode(PipelineContext<?> ctx) {
        for (PipelineListener listener : listeners) {
            listener.afterNode(ctx);
        }
    }

    /** FireOn记录错误 */
    private String fireOnError(PipelineContext<?> ctx, Throwable e) {
        String recoveryNodeId = null;
        for (PipelineListener listener : listeners) {
            String result = listener.onError(ctx, e);
            if (result != null && !result.isEmpty()) {
                recoveryNodeId = result;
            }
        }
        return recoveryNodeId;
    }

    /** FireOnComplete */
    private void fireOnComplete(PipelineContext<?> ctx) {
        for (PipelineListener listener : listeners) {
            listener.onComplete(ctx);
        }
    }

    /** FireOn开始 */
    private void fireOnStart(PipelineContext<?> ctx) {
        for (PipelineListener listener : listeners) {
            listener.onStart(ctx);
        }
    }

    /** FireOnDraw */
    private void fireOnDraw(PipelineContext<?> ctx) {
        for (PipelineListener listener : listeners) {
            listener.onDraw(ctx);
        }
    }

    /**
     * 带行计数的 println — 仅在 drawTree 模式下计数，printTree 正常调用不受影响。
     */
    private void treePrintln(String line) {
        System.out.println(line);
        if (countingTreeLines) {
            treeLineCounter++;
        }
    }

    /**
     * 绘制流水线 B+ 树拓扑结构 — 原地刷新模式。
     *
     * <p>使用 ANSI 转义序列将光标上移到上次树的位置，清除后重绘，
     * 视觉上始终只有一棵树在实时更新。</p>
     *
     * @param history      已执行节点 ID 列表
     * @param colorEnabled 是否启用 ANSI 颜色输出
     */
    @Override
    public void drawTree(List<String> history, boolean colorEnabled) {
        // 上移光标到上次树的位置，清除旧内容
        if (lastTreeLineCount > 0) {
            // ANSI: 上移 N 行 + 清除从光标到屏幕底部
            System.out.print("\033[" + lastTreeLineCount + "A\033[0J");
        }
        // 重绘树（带行计数）
        treeLineCounter = 0;
        countingTreeLines = true;
        printTree(history, colorEnabled);
        countingTreeLines = false;
        lastTreeLineCount = treeLineCounter;
        System.out.flush();
    }
}
