package com.chua.common.support.concurrent.backoff.provider;

import com.chua.common.support.concurrent.backoff.BackoffProvider;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 带抖动的指数退避提供者。
 *
 * <p>每次调用 {@link #nextDelay()} 自动递增内部尝试次数，
 * 在指数退避基础上添加随机抖动，避免惊群效应。</p>
 *
 * @author CH
 * @since 2026/07/24
 */
public class JitterBackoffProvider implements BackoffProvider {

    /**
     * 初始延迟（毫秒），默认 1000ms
     */
    private final long initialDelay;

    /**
     * 退避乘数，默认 2.0
     */
    private final double multiplier;

    /**
     * 最大延迟（毫秒），默认 30000ms
     */
    private final long maxDelay;

    /**
     * 抖动比例，默认 0.5（50%）
     */
    private final double jitterFactor;

    /**
     * 内部尝试次数计数器
     */
    private final AtomicInteger attempt = new AtomicInteger(0);

    /**
     * 创建默认抖动退避器（1s → 30s 上限，2x 增长，50% 抖动）。
     */
    public JitterBackoffProvider() {
        this(1000, 2.0, 30000, 0.5);
    }

    /**
     * 创建抖动退避器。
     *
     * @param initialDelay 初始延迟（毫秒）
     * @param multiplier   退避乘数
     * @param maxDelay     最大延迟（毫秒）
     * @param jitterFactor 抖动比例（0.0 ~ 1.0）
     */
    public JitterBackoffProvider(long initialDelay, double multiplier, long maxDelay, double jitterFactor) {
        this.initialDelay = initialDelay;
        this.multiplier = multiplier;
        this.maxDelay = maxDelay;
        this.jitterFactor = jitterFactor;
    }

    /**
     * 计算下一次避让的等待时间，内部自动递增尝试次数。
     *
     * @return 等待时间（毫秒）
     */
    public long nextDelay() {
        return nextDelay(attempt.getAndIncrement());
    }

    @Override
    /**
     * NextDelay
    */
    public long nextDelay(int attempt) {
        long base = (long) (initialDelay * Math.pow(multiplier, attempt));
        long capped = Math.min(base, maxDelay);
        long jitter = (long) (capped * jitterFactor);
        long randomJitter = ThreadLocalRandom.current().nextLong(jitter + 1);
        return capped - jitter + randomJitter;
    }

    /**
     * 重置内部尝试次数计数器。
     */
    public void reset() {
        attempt.set(0);
    }
}
