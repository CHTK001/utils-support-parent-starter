package com.chua.collapse.support;

import com.chua.common.support.concurrent.collapse.CollapseBatchFunction;
import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.common.support.concurrent.collapse.CollapseExecutor;
import com.chua.common.support.concurrent.collapse.CollapseExecutorFactory;
import com.chua.common.support.concurrent.collapse.CollapseFlow;
import com.chua.common.support.concurrent.collapse.CollapseResultMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CollapseFlow} 回归测试。
 *
 * @author CH
 * @since 2026/09/04
 */
class CollapseFlowTest {

    /**
     * B3：首次 execute 后设置 executorFactory 应抛 IllegalStateException（合约强制）
     */
    @Test
    void executorFactoryAfterExecuteThrows() throws Throwable {
        CollapseFlow<String, String> flow = CollapseFlow.of("b3-contract", keys -> "ok");
        flow.execute("first");
        assertThrows(IllegalStateException.class,
                () -> flow.executorFactory(new DefaultCollapseExecutorFactory()),
                "首次 execute 后设置 executorFactory 应抛 IllegalStateException");
    }

    /**
     * executorFactory 回调被真实使用：计数工厂恰好创建一次执行器（若非回调，计数为 0）
     */
    @Test
    void executorFactoryCallbackIsActuallyUsed() throws Exception {
        AtomicInteger createCalls = new AtomicInteger();
        CollapseExecutorFactory countingFactory = new DefaultCollapseExecutorFactory() {
            @Override
            public <I, O> CollapseExecutor<I, O> create(CollapseConfig config,
                                                        CollapseBatchFunction<I, O> batchFunction) {
                createCalls.incrementAndGet();
                return super.create(config, batchFunction);
            }

            @Override
            public <I, O> CollapseExecutor<I, O> create(CollapseConfig config,
                                                        CollapseResultMapper<I, O> resultMapper) {
                createCalls.incrementAndGet();
                return super.create(config, resultMapper);
            }
        };
        CollapseFlow<String, String> flow = CollapseFlow.of("callback-used", keys -> "batch");
        flow.threshold(2).collectingWaitTime(30).executorFactory(countingFactory);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                    return flow.execute("same-key");
                } catch (Throwable throwable) {
                    throw new RuntimeException(throwable);
                }
            }));
        }
        for (Future<String> future : futures) {
            assertEquals("batch", future.get(15, TimeUnit.SECONDS), "合并批结果应广播一致");
        }
        pool.shutdownNow();
        assertEquals(1, createCalls.get(), "回调工厂应恰好创建一次执行器（证明回调被真实使用）");
    }
}
