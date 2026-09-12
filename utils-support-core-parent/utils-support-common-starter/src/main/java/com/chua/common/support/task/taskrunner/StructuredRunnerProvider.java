package com.chua.common.support.task.taskrunner;

import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicInteger;

/**
* 结构化并发运行器提供者 — 基于 Java 25 {@link StructuredTaskScope} 的默认执行引擎。
*
* <p>调度模型（dataflow 就绪即跑）：</p>
* <ol>
*   <li>全部节点一次性 fork 进单个结构化作用域；每个节点先等待其全部前置完成，
*       就绪即执行——无层间硬屏障，独立分支互不拖延</li>
*   <li>节点间通过 {@link CompletableFuture} 传递结果；等待使用响应中断的
*       {@code get()}，取消/关停时不会悬挂</li>
*   <li>配合 {@code Joiner.allUntil} 实现策略驱动的提前终止：
*       达到目标成功率/阈值即取消剩余任务，不可能达标时同样提前退出</li>
*   <li>前置失败/跳过的节点级联 SKIPPED；SKIPPED 不计入完成策略分母</li>
* </ol>
*
* <p>单节点链路（熔断降级 → 超时 → 预算感知重试）由 {@link AbstractRunnerProvider} 组装。
* 本类无共享可变状态，实例可安全支撑并发的多次 运行。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("structured")
public class StructuredRunnerProvider extends AbstractRunnerProvider implements RunnerProvider {

    /**
    * 同步执行整个拓扑图。
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

        var ordered = graph.definitionsInExecutionOrder();
        var total = ordered.size();
        var policy = options.policy();
        var successTarget = Math.min(policy.requiredSuccessCount(total), total);
        var failLimit = Math.min(policy.allowedFailCount(total) + 1, total);

        var successCount = new AtomicInteger();
        var failedCount = new AtomicInteger();
        var values = new ConcurrentHashMap<String, Object>();
        var errors = new ConcurrentHashMap<String, Throwable>();
        var skipReasons = new ConcurrentHashMap<String, String>();
        var nodeDurations = new ConcurrentHashMap<String, Long>();
        var failureTrail = new ConcurrentLinkedQueue<Throwable>();
        var futures = new ConcurrentHashMap<String, CompletableFuture<TaskResult>>();

        // 先注册全部结果槽位，再统一 fork，保证后继等待时槽位必然存在
        for (var def : ordered) {
            futures.put(def.getId(), new CompletableFuture<>());
        }

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<Object>allUntil(subtask -> isEarlyExit(
                        successCount.get(), failedCount.get(), successTarget, failLimit)),
                cf -> cf.withThreadFactory(Thread.ofVirtual().name("task-runner-node", 0).factory()))) {

            for (var def : ordered) {
                scope.fork(() -> runNode(def, context, options, futures,
                        values, errors, skipReasons, nodeDurations, failureTrail,
                        successCount, failedCount));
            }
            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调度被中断", e);
        }

        var results = assembleResults(ordered, values, errors, skipReasons, nodeDurations, options);
        var executedTotal = successCount.get() + failedCount.get();
        var passed = policy.evaluate(successCount.get(), failedCount.get(), executedTotal);

        var duration = System.currentTimeMillis() - started;
        emit(options, RunnerEvent.runLevel(RunnerEvent.Type.RUN_COMPLETED,
                "success=" + passed + " durationMs=" + duration,
                System.currentTimeMillis()));

        Throwable firstError = null;
        if (!passed) {
            firstError = failureTrail.peek();
            if (firstError == null) {
                firstError = new IllegalStateException("完成策略评估未达标");
            }
        }
        return new RunResult(passed, graph.getName(), duration, results, firstError);
    }

    /**
    * 单个节点执行体：等待前置就绪 → 级联跳过判定 → 执行链 → 记录并回填结果槽位。
    *
    * <p>任何阶段抛出的异常都会转化为本节点的 FAILED 结果并完成槽位，
    * 保证后继节点永不悬挂。</p>
     */
    private Object runNode(TaskDefinition def, RunnerContext context, ExecutionOptions options,
                           Map<String, CompletableFuture<TaskResult>> futures,
                           Map<String, Object> values, Map<String, Throwable> errors,
                           Map<String, String> skipReasons, Map<String, Long> nodeDurations,
                           ConcurrentLinkedQueue<Throwable> failureTrail,
                           AtomicInteger successCount, AtomicInteger failedCount) {
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        TaskResult produced;
        try {
            produced = awaitDependenciesOrSkip(def, futures, skipReasons);
            if (produced == null) {
                emit(options, RunnerEvent.nodeLevel(RunnerEvent.Type.NODE_STARTED,
                        def.getId(), null, System.currentTimeMillis()));
                produced = executeNode(def, context, options);
                nodeDurations.put(def.getId(), produced.duration());
            }
        } catch (Throwable t) {
            // 防御：任何阶段的异常都不得悬挂后继节点
            produced = TaskResult.failed(def.getId(), unwrap(t), 0);
        }
        record(def.getId(), produced, options, values, errors, skipReasons,
                failureTrail, successCount, failedCount);
        futures.get(def.getId()).complete(produced);
        return null;
    }

    /**
    * 等待全部前置节点完成；任一前置非成功则本节点产出 SKIPPED 结果。
    *
    * <p>前置的成功结果已在各自执行路径写入 {@link RunnerContext}，
    * 本方法仅做就绪等待与级联跳过判定。</p>
    *
    * @return SKIPPED 结果表示应级联跳过；空 表示前置全部就绪、可执行
    * @throws InterruptedException 等待期间被中断（仅发生在取消/关停路径）
     */
    private TaskResult awaitDependenciesOrSkip(TaskDefinition def,
                                               Map<String, CompletableFuture<TaskResult>> futures,
                                               Map<String, String> skipReasons) throws Exception {
        for (var dep : def.getDependencies()) {
            TaskResult depResult;
            try {
                depResult = futures.get(dep).get();
            } catch (ExecutionException e) {
                throw new IllegalStateException("前置 " + dep + " 结果封装异常", e.getCause());
            }
            if (depResult.status() != TaskResult.Status.SUCCESS) {
                skipReasons.put(def.getId(),
                        "前置 " + dep + " 未成功(" + depResult.status().name().toLowerCase() + ")");
                return TaskResult.skipped(def.getId());
            }
        }
        return null;
    }

    /**
    * 记录节点结果：维护计数、收集表与生命周期事件。
     */
    private void record(String id, TaskResult result, ExecutionOptions options,
                        Map<String, Object> values, Map<String, Throwable> errors,
                        Map<String, String> skipReasons,
                        ConcurrentLinkedQueue<Throwable> failureTrail,
                        AtomicInteger successCount, AtomicInteger failedCount) {
        switch (result.status()) {
            case SUCCESS -> {
                values.put(id, result.data());
                successCount.incrementAndGet();
                emit(options, RunnerEvent.nodeLevel(RunnerEvent.Type.NODE_COMPLETED,
                        id, null, System.currentTimeMillis()));
            }
            case FAILED -> {
                errors.put(id, result.error());
                failedCount.incrementAndGet();
                failureTrail.add(result.error());
                emit(options, RunnerEvent.nodeLevel(RunnerEvent.Type.NODE_FAILED,
                        id, String.valueOf(result.error()), System.currentTimeMillis()));
            }
            case SKIPPED -> emit(options, RunnerEvent.nodeLevel(RunnerEvent.Type.NODE_SKIPPED,
                    id, skipReasons.getOrDefault(id, "被策略提前取消"), System.currentTimeMillis()));
            default -> throw new IllegalStateException("未知状态: " + result.status());
        }
    }

    /**
    * 按注册顺序汇总全部节点的最终结果。
    *
    * @param ordered       拓扑序节点定义
    * @param values        成功值收集表
    * @param errors        失败原因收集表
    * @param skipReasons   跳过原因收集表
    * @param nodeDurations 耗时收集表
    * @param options       执行参数（用于被取消节点的事件发布）
    * @return 全量结果列表
     */
    private List<TaskResult> assembleResults(List<TaskDefinition> ordered,
                                             Map<String, Object> values,
                                             Map<String, Throwable> errors,
                                             Map<String, String> skipReasons,
                                             Map<String, Long> nodeDurations,
                                             ExecutionOptions options) {
        var results = new ArrayList<TaskResult>(ordered.size());
        for (var def : ordered) {
            var id = def.getId();
            if (values.containsKey(id)) {
                results.add(TaskResult.success(id, values.get(id),
                        nodeDurations.getOrDefault(id, 0L)));
            } else if (errors.containsKey(id)) {
                results.add(TaskResult.failed(id, errors.get(id),
                        nodeDurations.getOrDefault(id, 0L)));
            } else if (skipReasons.containsKey(id)) {
                // SKIPPED 事件已在 record 阶段发布，此处仅补齐结果行
                results.add(TaskResult.skipped(id));
            } else {
                results.add(TaskResult.skipped(id));
                emit(options, RunnerEvent.nodeLevel(RunnerEvent.Type.NODE_SKIPPED,
                        id, "被策略提前取消", System.currentTimeMillis()));
            }
        }
        return results;
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
}
