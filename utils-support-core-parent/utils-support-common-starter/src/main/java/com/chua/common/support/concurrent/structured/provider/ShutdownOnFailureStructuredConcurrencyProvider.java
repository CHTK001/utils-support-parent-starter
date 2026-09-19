package com.chua.common.support.concurrent.structured.provider;

import com.chua.common.support.concurrent.structured.StructuredConcurrencyProvider;
import com.chua.common.support.utils.ThreadUtils;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于虚拟线程的 ShutdownOnFailure 结构化并发实现。
 *
 * <p>任一任务失败立即取消其余任务，并抛出第一个异常。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ShutdownOnFailureStructuredConcurrencyProvider implements StructuredConcurrencyProvider {

    /** 线程池执行器 */
    private final ExecutorService executor;
    /** 是否已关闭 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 创建 ShutdownOnFailureStructuredConcurrencyProvider 实例
     */
    public ShutdownOnFailureStructuredConcurrencyProvider() {
        this.executor = ThreadUtils.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 创建 ShutdownOnFailureStructuredConcurrencyProvider 实例
     * @param executor executor
     */
    public ShutdownOnFailureStructuredConcurrencyProvider(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    /** 提交 */
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
    /** 提交 */
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
    /** 合并 */
    public void join() throws Exception {
        executor.shutdown();
        executor.awaitTermination(60, TimeUnit.SECONDS);
    }

    @Override
    /** 关闭 */
    public void close() throws Exception {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }
}
