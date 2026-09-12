package com.chua.common.support.concurrent.backoff;

import com.chua.common.support.concurrent.backoff.provider.ExponentialBackoffProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
* 避让器门面，支持链式调用和受保护执行。
*
* <pre>{@code
* BackoffFlow.of("ws").initialDelay(1000).maxDelay(30000).sleep(attempt);
* BackoffFlow.of("ws").maxAttempts(5).execute(() -> sendMessage());
* BackoffFlow.of("ws").fallback(() -> logError()).maxAttempts(3).execute(() -> sendMessage());
* }</pre>
*
* @author CH
* @since 2026/07/24
 */
public final class BackoffFlow {

    /**
    * 避让器缓存，按名称索引
     */
    private static final Map<String, BackoffProvider> CACHE = new ConcurrentHashMap<>();

    /**
    * 避让器名称
     */
    private final String name;

    /**
    * 初始延迟（毫秒），默认 1000ms
     */
    private long initialDelay = 1000;

    /**
    * 退避乘数，默认 2.0
     */
    private double multiplier = 2.0;

    /**
    * 最大延迟（毫秒），默认 30000ms
     */
    private long maxDelay = 30000;

    /**
    * 最大尝试次数，默认 3
     */
    private int maxAttempts = 3;

    /**
    * 避让失败时的降级回调
     */
    private Supplier<Object> fallback;

    /**
    * 创建 BackoffFlow 实例
    * @param name name
     */
    private BackoffFlow(String name) {
        this.name = name;
    }

    /**
    * 创建避让门面实例。
    *
    * @param name 避让器名称
    * @return 门面实例
     */
    public static BackoffFlow of(String name) {
        return new BackoffFlow(name);
    }

    /**
    * 设置初始延迟。
    *
    * @param initialDelay 初始延迟（毫秒）
    * @return this
     */
    public BackoffFlow initialDelay(long initialDelay) {
        this.initialDelay = initialDelay;
        return this;
    }

    /**
    * 设置退避乘数。
    *
    * @param multiplier 退避乘数
    * @return this
     */
    public BackoffFlow multiplier(double multiplier) {
        this.multiplier = multiplier;
        return this;
    }

    /**
    * 设置最大延迟。
    *
    * @param maxDelay 最大延迟（毫秒）
    * @return this
     */
    public BackoffFlow maxDelay(long maxDelay) {
        this.maxDelay = maxDelay;
        return this;
    }

    /**
    * 设置最大尝试次数。
    *
    * @param maxAttempts 最大尝试次数
    * @return this
     */
    public BackoffFlow maxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
        return this;
    }

    /**
    * 设置降级回调。
    *
    * @param fallback 降级回调
    * @return this
     */
    public BackoffFlow fallback(Supplier<Object> fallback) {
        this.fallback = fallback;
        return this;
    }

    /**
    * 执行避让休眠。
    *
    * @param attempt 当前尝试次数
     */
    public void sleep(int attempt) {
        getProvider().sleep(attempt);
    }

    /**
    * 计算下一次避让延迟。
    *
    * @param attempt 当前尝试次数
    * @return 延迟时间（毫秒）
     */
    public long nextDelay(int attempt) {
        return getProvider().nextDelay(attempt);
    }

    /**
    * 在避让保护下执行任务。
    *
    * @param task 待执行任务
    * @param <T>  返回值类型
    * @return 任务结果，失败时返回降级回调结果
    * @throws Exception 任务执行异常
     */
    public <T> T execute(Callable<T> task) throws Exception {
        return execute(task, 0);
    }

    /**
    * 在避让保护下执行任务（无返回值）。
    *
    * @param task 待执行任务
     */
    public void execute(Runnable task) {
        execute(task, 0);
    }

    /** 执行 */
    private <T> T execute(Callable<T> task, int attempt) throws Exception {
        try {
            return task.call();
        } catch (Exception e) {
            if (attempt >= maxAttempts) {
                if (fallback != null) {
                    return (T) fallback.get();
                }
                throw e;
            }
            sleep(attempt);
            return execute(task, attempt + 1);
        }
    }

    /** 执行 */
    private void execute(Runnable task, int attempt) {
        try {
            task.run();
        } catch (Exception e) {
            if (attempt >= maxAttempts) {
                if (fallback != null) {
                    fallback.get();
                }
                return;
            }
            sleep(attempt);
            execute(task, attempt + 1);
        }
    }

    /**
    * 获取避让器提供者。
    *
    * @return 避让器实例
     */
    public BackoffProvider getProvider() {
        return CACHE.computeIfAbsent(name, k -> doCreate());
    }

    /**
    * 创建避让器提供者，优先使用 SPI 发现，否则回退到指数退避。
    *
    * @return 避让器实例
     */
    private BackoffProvider doCreate() {
        for (BackoffProvider provider : ServiceProvider.of(BackoffProvider.class).list().values()) {
            if (name.equals(provider.getClass().getSimpleName())) {
                return provider;
            }
        }
        return new ExponentialBackoffProvider(initialDelay, multiplier, maxDelay);
    }

    /**
    * 获取已缓存的避让器。
    *
    * @param name 避让器名称
    * @return 避让器实例，未找到返回 null
     */
    public static BackoffProvider get(String name) {
        return CACHE.get(name);
    }

    /**
    * 获取避让器提供者实例。
    *
    * @return BackoffProvider 实例
     */
    public BackoffProvider provider() {
        return getProvider();
    }

    /**
    * 移除避让器缓存。
    *
    * @param name 避让器名称
     */
    public static void remove(String name) {
        CACHE.remove(name);
    }

    /**
    * 清空所有缓存。
     */
    public static void clear() {
        CACHE.clear();
    }
}