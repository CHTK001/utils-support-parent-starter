package com.chua.common.support.concurrent.rate.provider;

import com.chua.common.support.concurrent.rate.RateLimiterProvider;
import com.google.common.util.concurrent.RateLimiter;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Guava RateLimiter 的限流器实现。
 *
 * <p>支持平滑突发限流（SmoothBursty）和预热限流（SmoothWarmingUp）两种模式。</p>
 *
 * @author CH
 * @since 2026/07/24
 */
public class GuavaRateLimiterProvider implements RateLimiterProvider {

    /** 名称 */
    private final String name;
    /** 限流器 */
    /** 比率limiter */
    private final RateLimiter rateLimiter;

    /**
     * 创建平滑突发限流器。
     *
     * @param name             限流器名称
     * @param permitsPerSecond 每秒许可数
     */
    public GuavaRateLimiterProvider(String name, double permitsPerSecond) {
        this.name = name;
        this.rateLimiter = RateLimiter.create(permitsPerSecond);
    }

    /**
     * 创建预热限流器。
     *
     * @param name             限流器名称
     * @param permitsPerSecond 每秒许可数
     * @param warmupPeriod     预热时间（秒）
     */
    public GuavaRateLimiterProvider(String name, double permitsPerSecond, long warmupPeriod) {
        this.name = name;
        this.rateLimiter = RateLimiter.create(permitsPerSecond, warmupPeriod, TimeUnit.SECONDS);
    }

    @Override
    public boolean tryAcquire() {
        return rateLimiter.tryAcquire();
    }

    @Override
    public boolean tryAcquire(long timeout, TimeUnit timeUnit) {
        return rateLimiter.tryAcquire(timeout, timeUnit);
    }

    @Override
    public int availablePermits() {
        return (int) rateLimiter.getRate();
    }

    @Override
    public String getName() {
        return name;
    }
}