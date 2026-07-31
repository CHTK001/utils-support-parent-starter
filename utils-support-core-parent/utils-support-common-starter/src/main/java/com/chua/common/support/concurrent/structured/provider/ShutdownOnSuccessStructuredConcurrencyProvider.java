package com.chua.common.support.concurrent.structured.provider;

import com.chua.common.support.concurrent.structured.StructuredConcurrencyProvider;
import com.chua.common.support.utils.ThreadUtils;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于虚拟线程的 ShutdownOnSuccess 结构化并发实现。
 *
 * <p>任一任务成功立即取消其余任务，返回第一个成功结果。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ShutdownOnSuccessStructuredConcurrencyProvider implements StructuredConcurrencyProvider {

    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ShutdownOnSuccessStructuredConcurrencyProvider() {
        this.executor = ThreadUtils.newVirtualThreadPerTaskExecutor();
    }

    public ShutdownOnSuccessStructuredConcurrencyProvider(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public <T> T submit(Callable<T> task) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("结构化并发已关闭");
        }
        Future<T> future = executor.submit(task);
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }

    @Override
    public void submit(Runnable task) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("结构化并发已关闭");
        }
        Future<?> future = executor.submit(task);
        try {
            future.get();
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }

    @Override
    public void join() throws Exception {
        executor.shutdown();
        executor.awaitTermination(60, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }
}
