package com.chua.common.support.task.taskrunner;

import com.chua.common.support.spi.annotations.Spi;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 结构化并发运行器提供者 — 基于 Java 25 {@link StructuredTaskScope} 的默认执行引擎。
 *
 * <p>调度模型：</p>
 * <ol>
 *   <li>Kahn 拓扑分层，第 0 层（最外层无依赖节点）全部并行 fork</li>
 *   <li>每层一个结构化作用域，配合 {@code Joiner.allUntil} 实现：
 *       达到策略目标成功率/阈值即提前取消剩余任务；不可能达标时同样提前终止</li>
 *   <li>层内评估完成策略，不达标即整体失败，后续层全部标记 SKIPPED</li>
 *   <li>失败节点若有 fallback 则以降级值记为成功，其下游照常执行</li>
 * </ol>
 *
 * <p>单节点链路（熔断降级 → 超时 → 重试）由 {@link AbstractRunnerProvider} 组装。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("structured")
public class StructuredRunnerProvider extends AbstractRunnerProvider implements RunnerProvider {

    /**
     * 同步执行整个拓扑图。
     *
     * <p>本方法无共享可变状态，Provider 实例可安全支撑并发的多次 run。</p>
     *
     * @param graph   已校验的任务拓扑图
     * @param context 运行上下文
     * @param options 执行参数
     * @return 整体运行结果，失败时携带首个错误与 SKIPPED 明细
     */
    @Override
    public RunResult run(TaskGraph graph, RunnerContext context, ExecutionOptions options) {
        var started = System.currentTimeMillis();
        emit(options, RunnerEvent.runLevel(RunnerEvent.Type.RUN_STARTED,
                "runner=" + graph.getName() + " nodes=" + graph.nodeIds().size(), started));

        var layers = graph.layeredTopology();
        var allResults = new ArrayList<TaskResult>();
        var unavailable = new HashSet<String>();
        Throwable firstError = null;
        var overallSuccess = true;

        for (var layer : layers) {
            var runnable = new ArrayList<TaskDefinition>();
            for (var def : layer) {
                if (unavailable.stream().anyMatch(def.getDependencies()::contains)) {
                    var skipped = TaskResult.skipped(def.getId());
                    allResults.add(skipped);
                    unavailable.add(def.getId());
                    emit(options, RunnerEvent.nodeLevel(RunnerEvent.Type.NODE_SKIPPED,
                            def.getId(), "前置依赖不可用", System.currentTimeMillis()));
                } else {
                    runnable.add(def);
                }
            }
            if (runnable.isEmpty()) {
                continue;
            }

            var outcome = executeLayer(runnable, context, options);
            allResults.addAll(outcome.results);
            outcome.results.stream()
                    .filter(r -> r.status() == TaskResult.Status.FAILED)
                    .forEach(r -> {
                        unavailable.add(r.id());
                        emit(options, RunnerEvent.nodeLevel(RunnerEvent.Type.NODE_FAILED,
                                r.id(), String.valueOf(r.error()), System.currentTimeMillis()));
                    });
            outcome.results.stream()
                    .filter(r -> r.status() == TaskResult.Status.SUCCESS)
                    .forEach(r -> emit(options, RunnerEvent.nodeLevel(RunnerEvent.Type.NODE_COMPLETED,
                            r.id(), null, System.currentTimeMillis())));

            if (firstError == null && !outcome.passed) {
                firstError = outcome.results.stream()
                        .filter(r -> r.status() == TaskResult.Status.FAILED)
                        .map(TaskResult::error)
                        .findFirst()
                        .orElse(null);
            }
            if (!outcome.passed) {
                overallSuccess = false;
                break;
            }
        }

        // 整体失败时，尚未执行的节点全部补记 SKIPPED
        if (!overallSuccess) {
            var finished = new HashSet<String>();
            allResults.forEach(r -> finished.add(r.id()));
            for (var id : graph.nodeIds()) {
                if (!finished.contains(id)) {
                    allResults.add(TaskResult.skipped(id));
                    unavailable.add(id);
                    emit(options, RunnerEvent.nodeLevel(RunnerEvent.Type.NODE_SKIPPED,
                            id, "整体已判败", System.currentTimeMillis()));
                }
            }
        }

        var duration = System.currentTimeMillis() - started;
        emit(options, RunnerEvent.runLevel(RunnerEvent.Type.RUN_COMPLETED,
                "success=" + overallSuccess + " durationMs=" + duration,
                System.currentTimeMillis()));

        return new RunResult(overallSuccess, graph.getName(), duration,
                List.copyOf(allResults), overallSuccess ? null
                : (firstError != null ? firstError : new IllegalStateException("完成策略评估未达标")));
    }

    /**
     * 执行一个拓扑层的并行批次并按策略评估。
     *
     * @param defs    本层节点定义，非空
     * @param context 运行上下文
     * @param options 执行参数
     * @return 层执行结果（含各节点明细与达标判定）
     */
    private LayerOutcome executeLayer(List<TaskDefinition> defs, RunnerContext context,
                                      ExecutionOptions options) {
        var total = defs.size();
        var policy = options.policy();
        var successTarget = Math.min(policy.requiredSuccessCount(total), total);
        var failLimit = Math.min(policy.allowedFailCount(total) + 1, total);

        var successCount = new AtomicInteger();
        var failedCount = new AtomicInteger();
        var values = new ConcurrentHashMap<String, Object>();
        var errors = new ConcurrentHashMap<String, Throwable>();

        // 执行局部耗时表：随本次 run 创建，Provider 实例保持无状态（并发 run 隔离）
        var nodeDurations = new ConcurrentHashMap<String, Long>();

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<Object>allUntil(subtask -> isEarlyExit(successCount.get(), failedCount.get(), successTarget, failLimit)),
                cf -> cf.withThreadFactory(Thread.ofVirtual().name("task-runner-node", 0).factory()))) {

            for (var def : defs) {
                scope.fork(() -> runForked(def, context, options, values, errors, successCount, failedCount, nodeDurations));
            }
            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调度被中断", e);
        }

        var results = new ArrayList<TaskResult>(total);
        for (var def : defs) {
            if (values.containsKey(def.getId())) {
                results.add(TaskResult.success(def.getId(), values.get(def.getId()),
                        nodeDurations.getOrDefault(def.getId(), 0L)));
            } else if (errors.containsKey(def.getId())) {
                results.add(TaskResult.failed(def.getId(), errors.get(def.getId()),
                        nodeDurations.getOrDefault(def.getId(), 0L)));
            } else {
                results.add(TaskResult.skipped(def.getId()));
                emit(options, RunnerEvent.nodeLevel(RunnerEvent.Type.NODE_SKIPPED,
                        def.getId(), "被策略提前取消", System.currentTimeMillis()));
            }
        }

        var executedTotal = successCount.get() + failedCount.get();
        var passed = policy.evaluate(successCount.get(), failedCount.get(), executedTotal);
        return new LayerOutcome(passed, results);
    }

    /**
     * 单个 fork 子任务体：记录开始事件、执行节点链、维护计数与耗时。
     *
     * <p>入口处含协作式中断检查点：任务已被提前取消时不再执行，
     * 避免取消后仍向收集表写入造成竞态。</p>
     *
     * @param def           节点定义
     * @param context       运行上下文
     * @param options       执行参数
     * @param values        成功值收集表（nodeId -> 结果）
     * @param errors        失败原因收集表（nodeId -> 异常）
     * @param successCount  成功计数器
     * @param failedCount   失败计数器
     * @param nodeDurations 本次执行的耗时收集表（nodeId -> 毫秒）
     * @return 恒为 null（结果经收集表传递）
     */
    private Object runForked(TaskDefinition def, RunnerContext context, ExecutionOptions options,
                             Map<String, Object> values, Map<String, Throwable> errors,
                             AtomicInteger successCount, AtomicInteger failedCount,
                             Map<String, Long> nodeDurations) {
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        emit(options, RunnerEvent.nodeLevel(RunnerEvent.Type.NODE_STARTED,
                def.getId(), null, System.currentTimeMillis()));
        var result = executeNode(def, context, options);
        nodeDurations.put(def.getId(), result.duration());
        if (Thread.currentThread().isInterrupted() && result.status() == TaskResult.Status.SUCCESS) {
            return null;
        }
        if (result.status() == TaskResult.Status.SUCCESS) {
            values.put(def.getId(), result.data());
            successCount.incrementAndGet();
        } else {
            errors.put(def.getId(), result.error());
            failedCount.incrementAndGet();
        }
        return null;
    }

    /**
     * 提前退出判定：达到成功目标或失败数越过容忍上限。
     *
     * @param success 当前成功数
     * @param failed  当前失败数
     * @param successTarget 成功目标数
     * @param failLimit 失败上限（超过即不可能达标）
     * @return true 表示应取消剩余任务
     */
    private boolean isEarlyExit(int success, int failed, int successTarget, int failLimit) {
        return success >= successTarget || failed >= failLimit;
    }

    /**
     * 层执行结果内部载体。
     *
     * @param passed  策略是否达标
     * @param results 本层各节点结果（含被取消的 SKIPPED）
     */
    private record LayerOutcome(boolean passed, List<TaskResult> results) {
    }
}
