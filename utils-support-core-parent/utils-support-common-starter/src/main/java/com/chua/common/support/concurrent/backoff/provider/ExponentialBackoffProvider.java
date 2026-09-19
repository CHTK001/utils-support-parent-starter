package com.chua.common.support.concurrent.backoff.provider;

import com.chua.common.support.concurrent.backoff.BackoffProvider;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 指数退避提供者。
 *
 * <p>每次调用 {@link #nextDelay()} 自动递增内部尝试次数，
 * 延迟 = {@code initialDelay * multiplier^attempt}，上限为 {@code maxDelay}。</p>
 *
 * @author CH
 * @since 2026/07/24
 */
public class ExponentialBackoffProvider implements BackoffProvider {

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
     * 内部尝试次数计数器
     */
    private final AtomicInteger attempt = new AtomicInteger(0);

    /**
     * 创建默认指数退避器（1s → 30s 上限，2x 增长）。
     */
    public ExponentialBackoffProvider() {
        this(1000, 2.0, 30000);
    }

    /**
     * 创建指数退避器。
     *
     * @param initialDelay 初始延迟（毫秒）
     * @param multiplier   退避乘数
     * @param maxDelay     最大延迟（毫秒）
     */
    public ExponentialBackoffProvider(long initialDelay, double multiplier, long maxDelay) {
        this.initialDelay = initialDelay;
        this.multiplier = multiplier;
        this.maxDelay = maxDelay;
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
    /** NextDelay */
    public long nextDelay(int attempt) {
        long delay = (long) (initialDelay * Math.pow(multiplier, attempt));
        return Math.min(delay, maxDelay);
    }

    /**
    * 重置内部尝试次数计数器。
    */
    public void reset() {
        attempt.set(0);
    }
}
