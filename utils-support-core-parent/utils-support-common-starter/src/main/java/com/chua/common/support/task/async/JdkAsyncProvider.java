package com.chua.common.support.task.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.chua.common.support.function.NamedThreadFactory;

/**
* JDK 默认异步提供者实现
*
* <p>基于 JDK {@link CompletableFuture} 实现的异步执行器。
*
* <p>实现说明：
* <ul>
*   <li>通过 {@link CompletableFuture#supplyAsync(Supplier, java.util.concurrent.Executor)} 提交任务</li>
*   <li>批量任务通过 {@link CompletableFuture#allOf(CompletableFuture[])} 等待所有完成</li>
* </ul>
*
* @author CH
* @since 1.0.0
 */
public class JdkAsyncProvider extends AbstractAsyncProvider {

    /**
    * 虚拟线程执行器
     */
    private static final ExecutorService VIRTUAL_EXECUTOR = new ThreadPoolExecutor(
            4, Runtime.getRuntime().availableProcessors() * 2,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new NamedThreadFactory("async"));

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
