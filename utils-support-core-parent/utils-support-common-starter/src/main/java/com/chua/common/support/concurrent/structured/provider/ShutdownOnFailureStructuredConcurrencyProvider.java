package com.chua.common.support.concurrent.structured.provider;

import com.chua.common.support.concurrent.structured.StructuredConcurrencyProvider;
import com.chua.common.support.utils.ThreadUtils;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NullUnmarked;

/**
 * 基于虚拟线程的 ShutdownOnFailure 结构化并发实现。
 *
 * <p>任一任务失败立即取消其余任务，并抛出第一个异常。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public class ShutdownOnFailureStructuredConcurrencyProvider implements StructuredConcurrencyProvider {

    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ShutdownOnFailureStructuredConcurrencyProvider() {
        this.executor = ThreadUtils.newVirtualThreadPerTaskExecutor();
    }

    public ShutdownOnFailureStructuredConcurrencyProvider(ExecutorService executor) {
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
