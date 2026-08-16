package com.chua.common.support.concurrent.threadflow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 响应式执行器，基于 {@link CompletableFuture} 异步执行。
 *
 * <p>使用 ForkJoinPool.commonPool() 作为默认线程池，
 * 每个任务通过 {@link CompletableFuture#supplyAsync} 提交。</p>
 *
 * @author CH
 * @since 2026/08/15
 */
public class ReactiveThreadExecutor extends AbstractThreadExecutor {

    public ReactiveThreadExecutor(ThreadStrategy strategy, int threshold, long timeout, TimeUnit timeUnit) {
        this(strategy, threshold, timeout, timeUnit, -1);
    }

    public ReactiveThreadExecutor(ThreadStrategy strategy, int threshold, long timeout, TimeUnit timeUnit,
                                  int maxConcurrent) {
        super(strategy, threshold, timeout, timeUnit, maxConcurrent);
    }

    @Override
    protected List<Future<Object>> submitTasks() {
        List<CompletableFuture<Object>> futures = new ArrayList<>(tasks.size());
        for (var task : tasks) {
            CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });
            futures.add(future);
        }
        return new ArrayList<>(futures);
    }

    @Override
    public void close() {
        // 无需显式释放
    }
}