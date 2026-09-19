package com.chua.common.support.concurrent.timeout;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 超时控制门面，支持链式调用、降级回调和受保护执行。
 *
 * <p>当任务执行超过指定时间时，自动中断并返回降级结果或抛出超时异常。
 * 防止慢调用拖垮调用方线程资源。</p>
 *
 * <pre>{@code
 * TimeoutFlow.of("api").timeout(3000).execute(() -> callApi());
 * TimeoutFlow.of("api").timeout(3, TimeUnit.SECONDS).fallback(() -> fallbackResult).execute(() -> callApi());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class TimeoutFlow {

    /**
     * 默认超时时间（毫秒）
     */
    private static final long DEFAULT_TIMEOUT = 3000;

    /**
     * 超时控制名称
     */
    private final String name;

    /**
     * 超时时间（毫秒）
     */
    private long timeoutMillis = DEFAULT_TIMEOUT;

    /**
     * 超时发生时的降级回调
     */
    private Supplier<Object> fallback;

    /**
     * 创建 TimeoutFlow 实例
     * @param name name
     */
    private TimeoutFlow(String name) {
        this.name = name;
    }

    /**
     * 创建超时控制门面实例。
     *
     * @param name 名称
     * @return 门面实例
     */
    public static TimeoutFlow of(String name) {
        return new TimeoutFlow(name);
    }

    /**
     * 设置超时时间（毫秒）。
     *
     * @param timeoutMillis 超时毫秒数
     * @return 当前门面
     */
    public TimeoutFlow timeout(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        return this;
    }

    /**
     * 设置超时时间。
     *
     * @param timeout   超时数值
     * @param timeUnit  时间单位
     * @return 当前门面
     */
    public TimeoutFlow timeout(long timeout, TimeUnit timeUnit) {
        this.timeoutMillis = timeUnit.toMillis(timeout);
        return this;
    }

    /**
     * 设置超时发生时的降级回调。
     *
     * <p>未配置降级回调时，超时直接抛出 {@link TimeoutException}。</p>
     *
     * @param fallback 降级回调
     * @return 当前门面
     */
    public TimeoutFlow fallback(Supplier<Object> fallback) {
        this.fallback = fallback;
        return this;
    }

    /**
     * 在超时保护下执行任务。
     *
     * @param callable 要执行的任务
     * @param <T>      返回值类型
     * @return 任务执行结果，超时返回降级结果
     * @throws TimeoutException 超时且未配置降级时抛出
     * @throws java.util.concurrent.ExecutionException 任务执行失败时抛出（原因为 cause）
     */
    public <T> T execute(Callable<T> callable) throws TimeoutException, java.util.concurrent.ExecutionException {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            try {
                return callable.call();
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            if (fallback != null) {
                @SuppressWarnings("unchecked")
                T result = (T) fallback.get();
                return result;
            }
            throw new TimeoutException("调用超时(" + timeoutMillis + "ms): " + name);
        } catch (java.util.concurrent.ExecutionException ee) {
            throw ee;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (fallback != null) {
                @SuppressWarnings("unchecked")
                T result = (T) fallback.get();
                return result;
            }
            throw new TimeoutException("调用被中断: " + name);
        }
    }
}
