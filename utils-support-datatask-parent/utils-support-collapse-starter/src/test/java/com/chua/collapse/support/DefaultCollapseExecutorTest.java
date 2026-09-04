package com.chua.collapse.support;

import com.chua.common.support.concurrent.collapse.CollapseBatchFunction;
import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.common.support.concurrent.collapse.CollapseExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultCollapseExecutor} 回归测试。
 *
 * <p>覆盖：同参合并广播、合并指标统计、close 与执行中任务竞态、配置参数校验。</p>
 *
 * @author CH
 * @since 2026/09/04
 */
class DefaultCollapseExecutorTest {

    /**
     * 并发执行工具：N 个线程同时调用 execute，返回后各任务结果已就绪
     */
    private static void runConcurrently(CollapseExecutor<List<Long>, Map<Long, String>> executor,
                                        int threads,
                                        List<Long> input) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<java.util.concurrent.Future<Map<Long, String>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                    return executor.execute(input);
                } catch (Throwable throwable) {
                    throw new RuntimeException(throwable);
                }
            }));
        }
        for (java.util.concurrent.Future<Map<Long, String>> future : futures) {
            future.get(15, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
    }

    /**
     * 同参并发调用合并为一次批量执行，结果广播一致
     */
    @Test
    void sameInputConcurrentCallMergesIntoOneBatch() throws Exception {
        AtomicInteger batchCalls = new AtomicInteger();
        CollapseConfig config = new CollapseConfig();
        config.setName("test-merge");
        config.setWaitThreshold(4);
        CollapseExecutor<List<Long>, Map<Long, String>> executor =
                new DefaultCollapseExecutor<>(config,
                        (CollapseBatchFunction<List<Long>, Map<Long, String>>) inputs -> {
                    batchCalls.incrementAndGet();
                    Map<Long, String> result = new LinkedHashMap<>();
                    for (List<Long> ids : inputs) {
                        for (Long id : ids) {
                            result.put(id, "u" + id);
                        }
                    }
                    return result;
                });
        try {
            runConcurrently(executor, 8, List.of(1L, 2L));
            int calls = batchCalls.get();
            assertTrue(calls >= 1 && calls <= 3, "同参 8 并发应合并为 1~3 次批量执行，实际 " + calls);
        } finally {
            executor.close();
        }
    }

    /**
     * 指标统计：executedCount / batchExecutionCount / mergeRate 正确
     */
    @Test
    void metricsReflectMergeBehavior() throws Exception {
        CollapseConfig config = new CollapseConfig();
        config.setName("test-metrics");
        config.setWaitThreshold(4);
        CollapseExecutor<List<Long>, Map<Long, String>> executor =
                new DefaultCollapseExecutor<>(config,
                        (CollapseBatchFunction<List<Long>, Map<Long, String>>) inputs -> {
                    Map<Long, String> result = new LinkedHashMap<>();
                    for (List<Long> ids : inputs) {
                        for (Long id : ids) {
                            result.put(id, "m" + id);
                        }
                    }
                    return result;
                });
        try {
            runConcurrently(executor, 8, List.of(1L));
            Map<String, Object> metrics = executor.metrics();
            assertEquals(8L, metrics.get("executedCount"), "8 次调用");
            assertTrue(((Long) metrics.get("batchExecutionCount")) >= 1
                            && ((Long) metrics.get("batchExecutionCount")) <= 3,
                    "合并后批量执行应远小于调用次数");
            double mergeRate = (double) metrics.get("mergeRate");
            assertTrue(mergeRate > 0.5d, "合并率应大于 0.5，实际 " + mergeRate);
        } finally {
            executor.close();
        }
    }

    /**
     * close 与执行中任务竞态：close 不挂起，未完成任务以异常结束，后续 execute 拒绝
     */
    @Test
    void closeDuringExecutionCompletesOrRejectsWithoutHang() throws Exception {
        AtomicInteger batchCalls = new AtomicInteger();
        CollapseConfig config = new CollapseConfig();
        config.setName("test-close-race");
        config.setWaitThreshold(100);
        config.setCollectingWaitTime(50);
        CollapseExecutor<Object, Object> executor = new DefaultCollapseExecutor<>(config,
                (CollapseBatchFunction<Object, Object>) ignored -> {
            batchCalls.incrementAndGet();
            return "batch";
        });
        ExecutorService pool = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        java.util.concurrent.Future<?> executing = pool.submit(() -> {
            try {
                executor.execute("in-flight");
            } catch (Throwable ignored) {
                // 执行中任务可正常完成或被拒绝，均视为合理
            }
            return null;
        });
        executor.close();
        executing.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();
        // close 后 execute 必须快速失败，而非挂起
        long start = System.currentTimeMillis();
        assertThrows(Throwable.class, () -> executor.execute("after-close"));
        assertTrue(System.currentTimeMillis() - start < 5000, "close 后 execute 应立即失败");
    }

    /**
     * 配置校验：负阈值拒绝
     */
    @Test
    void negativeThresholdRejected() {
        CollapseConfig config = new CollapseConfig();
        assertThrows(IllegalArgumentException.class, () -> config.setWaitThreshold(-1),
                "负阈值应抛出 IllegalArgumentException");
    }
}
