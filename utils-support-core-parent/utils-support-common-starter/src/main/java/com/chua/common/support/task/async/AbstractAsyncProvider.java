package com.chua.common.support.task.async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 异步提供者抽象基类
 *
 * <p>提供批量异步执行的默认实现，子类只需实现核心异步方法：
 * {@link #doSupply(Supplier)} 和 {@link #doRun(Runnable)}。
 *
 * @author CH
 * @since 1.0.0
*/
public abstract class AbstractAsyncProvider implements AsyncProvider {

    /**
    * 异步执行带返回值的任务，委托给 {@link #doSupply(Supplier)}。
    */
    @Override
    public <T> CompletableFuture<T> supply(Supplier<T> supplier) {
        return doSupply(supplier);
    }

    /**
    * 异步执行无返回值任务，委托给 {@link #doRun(Runnable)}。
    */
    @Override
    public CompletableFuture<Void> run(Runnable runnable) {
        return doRun(runnable);
    }

    /**
    * 批量异步执行并聚合全部结果：并发提交所有任务，
    * 全部完成后按提交顺序返回结果列表。
    * @param suppliers 供应商
    * @return supply全部的结果
    */
    @Override
    @SafeVarargs
    @SuppressWarnings("unchecked")
    public final <T> CompletableFuture<List<T>> supplyAll(Supplier<T>... suppliers) {
        List<CompletableFuture<T>> futures = new ArrayList<>(suppliers.length);
        for (Supplier<T> s : suppliers) {
            futures.add(supply(s));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
    }

    /**
    * 列表版批量异步执行，委托给数组版本。
    */
    @Override
    public <T> CompletableFuture<List<T>> supplyAll(List<Supplier<T>> suppliers) {
        return supplyAll(suppliers.toArray(new Supplier[0]));
    }

    /**
    * 异步执行带返回值任务的核心逻辑，由具体实现提供。
    *
    * @param supplier 业务逻辑
    * @param <T>      返回值类型
    * @return 异步结果
    */
    protected abstract <T> CompletableFuture<T> doSupply(Supplier<T> supplier);

    /**
    * 异步执行无返回值任务的核心逻辑，由具体实现提供。
    *
    * @param runnable 业务逻辑
    * @return 异步结果
    */
    protected abstract CompletableFuture<Void> doRun(Runnable runnable);
}
