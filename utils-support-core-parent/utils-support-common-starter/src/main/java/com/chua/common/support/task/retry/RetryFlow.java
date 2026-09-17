package com.chua.common.support.task.retry;

import com.chua.common.support.concurrent.backoff.BackoffFlow;
import com.chua.common.support.concurrent.backoff.BackoffProvider;

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
 * }</pre>
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
        this.fallback = fallback;
        return this;
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
