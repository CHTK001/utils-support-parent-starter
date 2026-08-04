package com.chua.common.support.task.async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.jspecify.annotations.NullUnmarked;

/**
 * 异步提供者抽象基类
 *
 * <p>提供批量异步执行的默认实现，子类只需实现核心异步方法：
 * {@link #doSupply(Supplier)} 和 {@link #doRun(Runnable)}。
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
public abstract class AbstractAsyncProvider implements AsyncProvider {

    @Override
    public <T> CompletableFuture<T> supply(Supplier<T> supplier) {
        return doSupply(supplier);
    }

    @Override
    public CompletableFuture<Void> run(Runnable runnable) {
        return doRun(runnable);
    }

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

    @Override
    public <T> CompletableFuture<List<T>> supplyAll(List<Supplier<T>> suppliers) {
        return supplyAll(suppliers.toArray(new Supplier[0]));
    }

    protected abstract <T> CompletableFuture<T> doSupply(Supplier<T> supplier);
    protected abstract CompletableFuture<Void> doRun(Runnable runnable);
}
