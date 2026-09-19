package com.chua.common.support.concurrent.backoff.provider;

import com.chua.common.support.concurrent.backoff.BackoffProvider;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 固定延迟避让提供者。
 *
 * <p>每次调用 {@link #nextDelay()} 返回固定延迟，内部自动递增尝试次数。</p>
 *
 * @author CH
 * @since 2026/07/24
 */
public class FixedBackoffProvider implements BackoffProvider {

    /**
     * 固定延迟（毫秒），默认 1000ms
     */
    private final long delay;

    /**
     * 内部尝试次数计数器
     */
    private final AtomicInteger attempt = new AtomicInteger(0);

    /**
     * 创建默认固定延迟避让器（1000ms）。
     */
    public FixedBackoffProvider() {
        this(1000);
    }

    /**
     * 创建固定延迟避让器。
     *
     * @param delay 固定延迟（毫秒）
     */
    public FixedBackoffProvider(long delay) {
        this.delay = delay;
    }

    /**
     * 计算下一次避让的等待时间，内部自动递增尝试次数。
     *
     * @return 等待时间（毫秒）
     */
    public long nextDelay() {
        attempt.getAndIncrement();
        return delay;
    }

    @Override
    /**
     * NextDelay
    */
    public long nextDelay(int attempt) {
        return delay;
    }

    /**
     * 重置内部尝试次数计数器。
     */
    public void reset() {
        attempt.set(0);
    }
}
