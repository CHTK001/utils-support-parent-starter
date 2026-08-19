package com.chua.common.support.concurrent.threadflow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 基于平台线程池的执行器。
 *
 * <p>使用 {@link Executors#newCachedThreadPool()} 作为默认线程池，
 * 也支持用户传入自定义 {@link ExecutorService}。</p>
 *
 * @author CH
 * @since 2026/08/15
 */
public class PlatformThreadExecutor extends AbstractThreadExecutor {

    /**
     * 线程池实例
     */
    private final ExecutorService executor;

    /**
     * 创建 PlatformThreadExecutor 实例
     * @param strategy strategy
     * @param int int
     * @param long long
     * @param TimeUnit TimeUnit
     */
    public PlatformThreadExecutor(ThreadStrategy strategy, int threshold, long timeout, TimeUnit timeUnit) {
        this(strategy, threshold, timeout, timeUnit, -1, Executors.newCachedThreadPool());
    }

    /**
     * 创建 PlatformThreadExecutor 实例
     * @param strategy strategy
     * @param threshold threshold
     * @param timeout timeout
     * @param timeUnit timeUnit
     * @param maxConcurrent maxConcurrent
     */
    public PlatformThreadExecutor(ThreadStrategy strategy, int threshold, long timeout, TimeUnit timeUnit,
                                  int maxConcurrent) {
        this(strategy, threshold, timeout, timeUnit, maxConcurrent, Executors.newCachedThreadPool());
    }

    /**
     * 创建 PlatformThreadExecutor 实例
     * @param strategy strategy
     * @param threshold threshold
     * @param timeout timeout
     * @param timeUnit timeUnit
     * @param maxConcurrent maxConcurrent
     * @param executor executor
     */
    public PlatformThreadExecutor(ThreadStrategy strategy, int threshold, long timeout, TimeUnit timeUnit,
                                  int maxConcurrent, ExecutorService executor) {
        super(strategy, threshold, timeout, timeUnit, maxConcurrent);
        this.executor = executor;
    }

    @Override
    /** 提交Tasks */
    protected List<Future<Object>> submitTasks() {
        List<Future<Object>> futures = new ArrayList<>(tasks.size());
        for (var task : tasks) {
            futures.add(executor.submit(task));
        }
        return futures;
    }

    @Override
    /** 关闭 */
    public void close() {
        executor.shutdownNow();
    }
}