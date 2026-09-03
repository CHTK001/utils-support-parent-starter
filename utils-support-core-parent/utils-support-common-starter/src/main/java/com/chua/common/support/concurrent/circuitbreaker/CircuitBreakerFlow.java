package com.chua.common.support.concurrent.circuitbreaker;

import com.chua.common.support.concurrent.circuitbreaker.provider.InMemoryCircuitBreakerProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 熔断降级门面，支持链式调用、降级回调和受保护执行。
 *
 * <pre>{@code
 * CircuitBreakerFlow.of("api").tryAcquire();
 * CircuitBreakerFlow.of("api").execute(() -> doSomething());
 * CircuitBreakerFlow.of("api").fallback(() -> fallbackResult).execute(() -> doSomething());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class CircuitBreakerFlow {

    /**
     * 熔断器缓存，按名称索引
     */
    private static final Map<String, CircuitBreakerProvider> CACHE = new ConcurrentHashMap<>();

    /**
     * 熔断器名称
     */
    private final String name;

    /**
     * 失败阈值，达到此次数后熔断打开
     */
    private int failureThreshold = 5;

    /**
     * 成功阈值，达到此次数后熔断关闭
     */
    private int successThreshold = 2;

    /**
     * 熔断打开后的等待时间（毫秒），之后进入半开状态
     */
    private long waitDuration = 60000;

    /**
     * 熔断拒绝时的降级回调
     */
    private Supplier<Object> fallback;

    /**
     * 创建 CircuitBreakerFlow 实例
     * @param name name
     */
    private CircuitBreakerFlow(String name) {
        this.name = name;
    }

    /**
     * 创建熔断门面实例。
     *
     * @param name 熔断器名称
     * @return 门面实例
     */
    public static CircuitBreakerFlow of(String name) {
        return new CircuitBreakerFlow(name);
    }

    /**
     * 设置失败阈值。
     *
     * @param failureThreshold 失败次数
     * @return 当前门面
     */
    public CircuitBreakerFlow failureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
        return this;
    }

    /**
     * 设置成功阈值。
     *
     * @param successThreshold 成功次数
     * @return 当前门面
     */
    public CircuitBreakerFlow successThreshold(int successThreshold) {
        this.successThreshold = successThreshold;
        return this;
    }

    /**
     * 设置熔断打开后的等待时间（毫秒）。
     *
     * @param waitDuration 等待时间（毫秒）
     * @return 当前门面
     */
    public CircuitBreakerFlow waitDuration(long waitDuration) {
        this.waitDuration = waitDuration;
        return this;
    }

    /**
     * 设置熔断拒绝时的降级回调。
     *
     * @param fallback 降级回调
     * @return 当前门面
     */
    public CircuitBreakerFlow fallback(Supplier<Object> fallback) {
        this.fallback = fallback;
        return this;
    }

    /**
     * 尝试获取执行许可。
     *
     * @return 获取成功返回 true
     */
    public boolean tryAcquire() {
        CircuitBreakerProvider provider = getProvider();
        return provider.tryAcquire();
    }

    /**
     * 在熔断保护下执行任务。
     *
     * @param callable 要执行的任务
     * @param <T>      返回值类型
     * @return 任务执行结果，熔断拒绝时返回降级结果
     */
    public <T> T execute(Callable<T> callable) {
        CircuitBreakerProvider provider = getProvider();
        if (!provider.tryAcquire()) {
            return onRejected();
        }
        try {
            T result = callable.call();
            provider.recordSuccess();
            return result;
        } catch (Exception e) {
            provider.recordFailure();
            if (fallback != null) {
                return onRejected();
            }
            throw new RuntimeException("熔断执行失败: " + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    /** OnRejected */
    private <T> T onRejected() {
        if (fallback != null) {
            return (T) fallback.get();
        }
        throw new IllegalStateException("熔断拒绝: " + name);
    }

    /** 获取Provider */
    private CircuitBreakerProvider getProvider() {
        return CACHE.computeIfAbsent(name, this::createProvider);
    }

    /** 创建Provider */
    private CircuitBreakerProvider createProvider(String name) {
        CircuitBreakerProvider provider = ServiceProvider.of(CircuitBreakerProvider.class).getExtension(name);
        if (provider != null) {
            return provider;
        }
        return new InMemoryCircuitBreakerProvider(name, failureThreshold, successThreshold, waitDuration);
    }

    /**
     * 获取已缓存的熔断器。
     *
     * @param name 熔断器名称
     * @return 熔断器实例，未找到返回 null
     */
    public static CircuitBreakerProvider get(String name) {
        return CACHE.get(name);
    }

    /**
     * 移除指定熔断器缓存。
     *
     * @param name 熔断器名称
     */
    public static void remove(String name) {
        CACHE.remove(name);
    }

    /**
     * 清空所有熔断器缓存。
     */
    public static void clear() {
        CACHE.clear();
    }

    /**
     * 列出所有已缓存的熔断器（按名称索引）。
     *
     * @return 熔断器名称 → 实例映射
     */
    public static Map<String, CircuitBreakerProvider> list() {
        return new java.util.HashMap<>(CACHE);
    }
}