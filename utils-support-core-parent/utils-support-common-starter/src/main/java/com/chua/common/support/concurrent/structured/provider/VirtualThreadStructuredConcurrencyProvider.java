package com.chua.common.support.concurrent.structured.provider;

import com.chua.common.support.concurrent.structured.StructuredConcurrencyProvider;
import com.chua.common.support.utils.ThreadUtils;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于虚拟线程的结构化并发实现。
 *
 * <p>使用 JDK 21 虚拟线程执行器，提供轻量级并发任务管理。</p>
 *
 * @author CH
 * @since 2026/07/24
 */
public class VirtualThreadStructuredConcurrencyProvider implements StructuredConcurrencyProvider {

    /**
     * 虚拟线程执行器
     */
    private final ExecutorService executor;

    /**
     * 是否已关闭
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 创建默认虚拟线程结构化并发器。
     */
    public VirtualThreadStructuredConcurrencyProvider() {
        this.executor = ThreadUtils.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 创建 VirtualThreadStructuredConcurrencyProvider 实例
     * @param executor executor
     */
    public VirtualThreadStructuredConcurrencyProvider(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    /** 提交 */
    public <T> T submit(Callable<T> task) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("结构化并发已关闭");
        }
        return executor.submit(task).get();
    }

    @Override
    /** 提交 */
    public void submit(Runnable task) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("结构化并发已关闭");
        }
        executor.submit(task).get();
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