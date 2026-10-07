package com.chua.common.support.task.async;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 异步流管理器
 *
 * <p>异步执行的门面类，提供简洁易用的异步任务执行 API。
 * 内部封装了 {@link AsyncProvider} 的执行能力，对外提供统一的异步入口。
 *
 * <p>核心能力：
 * <ul>
 *   <li><strong>单个异步任务</strong>：通过 {@link #supply(Supplier)} 或 {@link #run(Runnable)}</li>
 *   <li><strong>批量异步任务</strong>：通过 {@link #supplyAll(Supplier...)} 并发执行多个任务</li>
 *   <li><strong>链式任务</strong>：通过 {@link #task(Supplier)} / {@link #task(Runnable)}
 *       组成顺序执行的任务链，{@code execute} 同步执行、
 *       {@code executeAsync} 以 Reactor 三回调交付（见 {@link AsyncFlowChain}）</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * AsyncFlow flow = AsyncFlow.of();
 * CompletableFuture<String> future = flow.supply(() -> fetchData());
 * CompletableFuture<List<String>> batch = flow.supplyAll(
 *     () -> api.call("/a"),
 *     () -> api.call("/b"),
 *     () -> api.call("/c")
 * );
 * }</pre>
 *
 * <p>链式任务示例：
 * <pre>{@code
 * Mono<List<Object>> mono = AsyncFlow.of()
 *         .task(() -> loadConfig())
 *         .task(cfg -> query(cfg))
 *         .executeAsync();
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
public class AsyncFlow {

    /**
     * 底层异步提供者
     */
    private final AsyncProvider provider;

    /**
     * 创建默认的异步流管理器。
     *
     * <p>使用 {@link JdkAsyncProvider} 作为默认异步实现。</p>
     *
     * @return AsyncFlow 实例
     */
    public static AsyncFlow of() {
        return new AsyncFlow();
    }

    /**
     * 创建默认的异步流管理器。
     */
    public AsyncFlow() {
        this(new JdkAsyncProvider());
    }

    /**
     * 创建指定提供者的异步流管理器
     *
     * @param provider 异步提供者
     */
    public AsyncFlow(AsyncProvider provider) {
        this.provider = provider;
    }

    /**
     * 异步执行带返回值的任务
     *
     * @param <T>      返回值类型
     * @param supplier 任务提供者
     * @return 异步计算结果
     */
    public <T> CompletableFuture<T> supply(Supplier<T> supplier) {
        return provider.supply(supplier);
    }

    /**
     * 异步执行无返回值的任务
     *
     * @param runnable 待执行的任务
     * @return 异步计算标志
     */
    public CompletableFuture<Void> run(Runnable runnable) {
        return provider.run(runnable);
    }

    /**
     * 批量异步执行多个任务并汇总结果
     *
     * @param <T>       返回值类型
     * @param suppliers 任务提供者数组
     * @return 所有任务结果的异步列表
     */
    @SafeVarargs
    @SuppressWarnings("unchecked")
    public final <T> CompletableFuture<List<T>> supplyAll(Supplier<T>... suppliers) {
        return provider.supplyAll(suppliers);
    }

    /**
     * 批量异步执行多个任务并汇总结果
     *
     * @param <T>       返回值类型
     * @param suppliers 任务提供者列表
     * @return 所有任务结果的异步列表
     */
    public <T> CompletableFuture<List<T>> supplyAll(List<Supplier<T>> suppliers) {
        return provider.supplyAll(suppliers);
    }

    /**
     * 追加链式任务的第一步（带返回值）。
     *
     * <p>由此进入 {@link AsyncFlowChain} 链式定义，
     * 与后续任务一起由 {@code execute}（同步）或 {@code executeAsync}（Reactor 三回调）执行。
     *
     * @param <R>  产出类型
     * @param task 任务
     * @return 任务链（类型为 {@code R}）
     */
    public <R> AsyncFlowChain<R> task(Supplier<? extends R> task) {
        return new AsyncFlowChain<R>(this).task(task);
    }

    /**
     * 追加链式任务的第一步（无返回值）。
     *
     * @param task 任务
     * @return 任务链
     */
    public AsyncFlowChain<Object> task(Runnable task) {
        return new AsyncFlowChain<Object>(this).task(task);
    }

    /**
     * 获取底层异步提供者
     *
     * @return 异步提供者
     */
    public AsyncProvider getProvider() {
        return provider;
    }
}
