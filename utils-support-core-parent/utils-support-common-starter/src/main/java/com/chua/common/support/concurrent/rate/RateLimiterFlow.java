package com.chua.common.support.concurrent.rate;

import com.chua.common.support.concurrent.rate.provider.GuavaRateLimiterProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 限流器门面，支持链式调用、降级回调和受保护执行。
 *
 * <pre>{@code
 * RateLimiterFlow.of("api", 100.0).tryAcquire();
 * RateLimiterFlow.of("api", 100.0).timeout(500, TimeUnit.MILLISECONDS).execute(() -> doSomething());
 * RateLimiterFlow.of("api", 100.0).fallback(() -> fallbackResult).execute(() -> doSomething());
 * }</pre>
 *
 * @author CH
 * @since 2026/07/24
 */
public final class RateLimiterFlow {

    /**
     * 限流器缓存，按名称索引
     */
    private static final Map<String, RateLimiterProvider> CACHE = new ConcurrentHashMap<>();

    /**
     * 限流器名称
     */
    private final String name;

    /**
     * 每秒许可数
     */
    private final double permitsPerSecond;

    /**
     * 预热时间（秒），0 表示不预热
     */
    private long warmupPeriod;

    /**
     * 获取许可的超时时间
     */
    private long timeout;

    /**
     * 超时时间单位
     */
    private TimeUnit timeUnit;

    /**
     * 限流拒绝时的降级回调
     */
    private Supplier<Object> fallback;

    /**
     * 创建 RateLimiterFlow 实例
     * @param name name
     * @param double double
     */
    private RateLimiterFlow(String name, double permitsPerSecond) {
        this.name = name;
        this.permitsPerSecond = permitsPerSecond;
    }

    /**
     * 创建限流门面实例。
     *
     * @param name             限流器名称
     * @param permitsPerSecond 每秒许可数
     * @return 门面实例
     */
    public static RateLimiterFlow of(String name, double permitsPerSecond) {
        return new RateLimiterFlow(name, permitsPerSecond);
    }

    /**
     * 设置预热时间。
     *
     * @param warmupPeriod 预热时间（秒）
     * @return this
     */
    public RateLimiterFlow warmup(long warmupPeriod) {
        this.warmupPeriod = warmupPeriod;
        return this;
    }

    /**
     * 设置获取许可的超时时间。
     *
     * @param timeout  超时时间
     * @param timeUnit 时间单位
     * @return this
     */
    public RateLimiterFlow timeout(long timeout, TimeUnit timeUnit) {
        this.timeout = timeout;
        this.timeUnit = timeUnit;
        return this;
    }

    /**
     * 设置限流拒绝时的降级回调。
     *
     * @param fallback 降级回调，限流拒绝时执行
     * @return this
     */
    public RateLimiterFlow fallback(Supplier<Object> fallback) {
        this.fallback = fallback;
        return this;
    }

    /**
     * 尝试获取许可。
     *
     * @return 获取成功返回 true
     */
    public boolean tryAcquire() {
        RateLimiterProvider provider = getProvider();
        if (timeout > 0 && timeUnit != null) {
            return provider.tryAcquire(timeout, timeUnit);
        }
        return provider.tryAcquire();
    }

    /**
     * 在限流保护下执行任务，拒绝时触发降级回调。
     *
     * @param task 待执行任务
     * @param <T>  返回值类型
     * @return 任务结果，拒绝时返回降级回调结果
     * @throws Exception 任务执行异常
     */
    public <T> T execute(Callable<T> task) throws Exception {
        if (tryAcquire()) {
            return task.call();
        }
        return onRejected();
    }

    /**
     * 在限流保护下执行任务（无返回值），拒绝时静默跳过。
     *
     * @param runnable 待执行任务
     */
    public void execute(Runnable runnable) {
        if (tryAcquire()) {
            runnable.run();
        }
    }

    /**
     * 执行降级回调。
     *
     * @param <T> 返回值类型
     * @return 降级回调结果，未设置回调返回 null
     */
    private <T> T onRejected() {
        if (fallback != null) {
            return (T) fallback.get();
        }
        return null;
    }

    /**
     * 从缓存获取或创建限流器提供者。
     *
     * @return 限流器实例
     */
    private RateLimiterProvider getProvider() {
        return CACHE.computeIfAbsent(name, k -> doCreate());
    }

    /**
     * 创建限流器提供者，优先使用 SPI 发现，否则回退到 Guava 实现。
     *
     * @return 限流器实例
     */
    private RateLimiterProvider doCreate() {
        for (RateLimiterProvider provider : ServiceProvider.of(RateLimiterProvider.class).list().values()) {
            if (name.equals(provider.getName())) {
                return provider;
            }
        }
        if (warmupPeriod > 0) {
            return new GuavaRateLimiterProvider(name, permitsPerSecond, warmupPeriod);
        }
        return new GuavaRateLimiterProvider(name, permitsPerSecond);
    }

    /**
     * 获取已缓存的限流器。
     *
     * @param name 限流器名称
     * @return 限流器实例，未找到返回 null
     */
    public static RateLimiterProvider get(String name) {
        return CACHE.get(name);
    }

    /**
     * 列出所有已缓存的限流器（按名称索引）。
     *
     * @return 限流器名称 → 实例映射
     */
    public static Map<String, RateLimiterProvider> list() {
        return new java.util.HashMap<>(CACHE);
    }

    /**
     * 获取限流器提供者实例。
     *
     * @return RateLimiterProvider 实例
     */
    public RateLimiterProvider provider() {
        return getProvider();
    }

    /**
     * 移除限流器缓存。
     *
     * @param name 限流器名称
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