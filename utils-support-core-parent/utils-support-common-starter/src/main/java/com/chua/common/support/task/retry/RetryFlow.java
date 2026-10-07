package com.chua.common.support.task.retry;

import com.chua.common.support.concurrent.backoff.BackoffFlow;
import com.chua.common.support.concurrent.backoff.BackoffProvider;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 重试流门面，支持链式调用、降级回调和受保护执行。
 *
 * <pre>{@code
 * RetryFlow.of("api").maxRetries(5).backoff(backoffProvider).execute(() -> callApi());
 * RetryFlow.of("api").maxRetries(3).fallback(() -> fallbackResult).execute(() -> callApi());
 * }</pre>back(() -> fallbackResult).execute(() -> callApi());
 * }
 *
 * @since 2026/07/24
 * @author CH
 */
public final class RetryFlow {

    /**
     * 重试器名称
     */
    private final String name;

    /**
     * 最大重试次数，默认 3
     */
    private int maxRetries = 3;

    /**
     * 避让器提供者
     */
    private BackoffProvider backoff;

    /**
     * 异常过滤器
     */
    private Predicate<Throwable> retryOnException;

    /**
     * 重试监听回调
     */
    private RetryListener retryListener;

    /**
     * 降级回调
     */
    private Supplier<Object> fallback;

    /**
     * 冻结标记：异步执行装配后禁止再改配置
     */
    private boolean frozen;

    /**
     * 创建 重试流 实例
     * @param name 名称
     */
    private RetryFlow(String name) {
        this.name = name;
    }

    /**
     * 重试监听接口。
     * @author CH
     * @since 4.0.0
     */
    @FunctionalInterface
    public interface RetryListener {
        void onRetry(int attemptNumber, Throwable cause);
    }

    /**
     * 创建重试门面实例。
     *
     * @param name 重试器名称
     * @return 门面实例
     */
    public static RetryFlow of(String name) {
        return new RetryFlow(name);
    }

    /**
     * 设置最大重试次数。
     *
     * @param maxRetries 最大重试次数
     * @return this
     */
    public RetryFlow maxRetries(int maxRetries) {
        checkMutable();
        this.maxRetries = maxRetries;
        return this;
    }

    /**
     * 设置避让器提供者。
     *
     * @param backoff 避让器
     * @return this
     */
    public RetryFlow backoff(BackoffProvider backoff) {
        checkMutable();
        this.backoff = backoff;
        return this;
    }

    /**
     * 设置异常过滤器。
     *
     * @param retryOnException 异常过滤器，返回 true 表示需要重试
     * @return this
     */
    public RetryFlow retryOnException(Predicate<Throwable> retryOnException) {
        checkMutable();
        this.retryOnException = retryOnException;
        return this;
    }

    /**
     * 设置重试监听回调。
     *
     * @param retryListener 重试监听
     * @return this
     */
    public RetryFlow retryListener(RetryListener retryListener) {
        checkMutable();
        this.retryListener = retryListener;
        return this;
    }

    /**
     * 设置降级回调。
     *
     * @param fallback 降级回调
     * @return this
     */
    public RetryFlow fallback(Supplier<Object> fallback) {
        checkMutable();
        this.fallback = fallback;
        return this;
    }

    /**
     * 异步装配后禁止再改配置。
     */
    private void checkMutable() {
        if (frozen) {
            throw new IllegalStateException("重试流已装配异步执行，不能再改配置");
        }
    }

    /**
     * 执行带重试能力的任务。
     *
     * @param task 待执行任务
     * @param <T>  返回值类型
     * @return 任务结果
     * @throws Exception 所有重试均失败后抛出最后一次异常
     */
    public <T> T execute(Callable<T> task) throws Exception {
        BackoffProvider provider = resolveBackoff();
        Exception lastException = null;
        for (int i = 0; i <= maxRetries; i++) {
            try {
                return task.call();
            } catch (Exception e) {
                lastException = e;
                if (retryOnException != null && !retryOnException.test(e)) {
                    throw e;
                }
                if (i >= maxRetries) {
                    if (fallback != null) {
                        return (T) fallback.get();
                    }
                    throw e;
                }
                if (retryListener != null) {
                    retryListener.onRetry(i + 1, e);
                }
                provider.sleep(i);
            }
        }
        throw new RetryException(maxRetries, lastException);
    }

    /**
     * 执行带重试能力的无返回值任务。
     *
     * @param task 待执行任务
     * @throws Exception 所有重试均失败后抛出最后一次异常
     */
    public void execute(Runnable task) throws Exception {
        execute(() -> {
            task.run();
            return null;
        });
    }

    /**
     * 异步执行带重试能力的任务。
     *
     * <p>与 {@link #execute(Callable)} 同一语义，整体搬到 {@code boundedElastic}
     * 调度线程执行（含退避休眠），以 Reactor 三个回调交付：
     * <ul>
     *   <li>{@code onNext}：任务结果（含降级返回）</li>
     *   <li>{@code onError}：全部重试失败后的最后一次异常，
     *       或不满足重试条件的异常，后续尝试不再执行</li>
     *   <li>{@code onComplete}：流正常结束</li>
     * </ul>
     *
     * <p>冷流：装配时即冻结配置（后续再调链式配置方法抛 {@link IllegalStateException}），
     * 每次订阅重新执行一遍。
     *
     * @param task 待执行任务
     * @param <T>  返回值类型
     * @return 任务结果的冷流
     */
    public <T> Mono<T> executeAsync(Callable<T> task) {
        frozen = true;
        return Mono.fromCallable(() -> execute(task))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 异步执行带重试能力的无返回值任务。
     *
     * @param task 待执行任务
     * @return 完成信号的冷流
     */
    public Mono<Void> executeAsync(Runnable task) {
        return executeAsync(() -> {
            task.run();
            return null;
        }).then();
    }

    /**
     * 解析退避
     *
     * @return resolve退避的结果
     */
    private BackoffProvider resolveBackoff() {
        if (backoff != null) {
            return backoff;
        }
        return BackoffFlow.of(name).getProvider();
    }

    /**
     * 获取重试提供者实例。
     *
     * @return BackoffProvider 实例
     */
    public BackoffProvider provider() {
        return resolveBackoff();
    }
}
