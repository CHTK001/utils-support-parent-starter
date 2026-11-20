package com.chua.common.support.task.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * JDK 默认异步提供者实现
 *
 * <p>基于 JDK {@link CompletableFuture} 实现的异步执行器。
 *
 * <p>实现说明：
 * <ul>
 *   <li>通过 {@link CompletableFuture#supplyAsync(Supplier, java.util.concurrent.Executor)} 提交任务</li>
 *   <li>批量任务通过 {@link CompletableFuture#allOf(CompletableFuture[])} 等待所有完成</li>
 *   <li>任务在虚拟线程上执行：每个任务一个虚拟线程，无固定线程池与任务队列。
 *       相比固定大小平台线程池，空闲时不再常驻 4~2×CPU 个平台线程栈
 *       （每线程约 1MB 栈保留），阻塞型任务也不会占满配额后排队等待</li>
 * </ul>
 *
 * @author CH
 * @since 1.0.0
 */
public class JdkAsyncProvider extends AbstractAsyncProvider {

    /**
     * 虚拟线程执行器：每任务一个虚拟线程，由 JVM 调度，无队列堆积
     */
    // [P3C C1 豁免] 场景化创建线程，需文档化
    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("async-", 0).factory());

    /**
     * 异步执行带返回值的任务
     *
     * @param <T>      返回值类型
     * @param supplier 任务提供者
     * @return 异步计算结果
     */
    @Override
    protected <T> CompletableFuture<T> doSupply(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, VIRTUAL_EXECUTOR);
    }

    /**
     * 基于共享虚拟线程执行器运行任务。
     */
    @Override
    protected CompletableFuture<Void> doRun(Runnable runnable) {
        return CompletableFuture.runAsync(runnable, VIRTUAL_EXECUTOR);
    }
}
