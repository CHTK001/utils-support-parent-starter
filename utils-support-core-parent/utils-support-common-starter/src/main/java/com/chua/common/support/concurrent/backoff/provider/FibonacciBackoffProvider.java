package com.chua.common.support.concurrent.backoff.provider;

import com.chua.common.support.concurrent.backoff.BackoffProvider;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 斐波那契退避提供者。
 *
 * <p>每次调用 {@link #nextDelay()} 自动递增内部尝试次数，
 * 延迟 = {@code initialDelay * fib(attempt + 1)}，上限为 {@code maxDelay}。</p>
 *
 * @author CH
 * @since 2026/07/24
 */
public class FibonacciBackoffProvider implements BackoffProvider {

    /**
     * 初始延迟（毫秒），默认 1000ms
     */
    private final long initialDelay;

    /**
     * 最大延迟（毫秒），默认 30000ms
     */
    private final long maxDelay;

    /**
     * 内部尝试次数计数器
     */
    private final AtomicInteger attempt = new AtomicInteger(0);

    /**
     * 创建默认斐波那契退避器（1s → 30s 上限）。
     */
    public FibonacciBackoffProvider() {
        this(1000, 30000);
    }

    /**
     * 创建斐波那契退避器。
     *
     * @param initialDelay 初始延迟（毫秒）
     * @param maxDelay     最大延迟（毫秒）
     */
    public FibonacciBackoffProvider(long initialDelay, long maxDelay) {
        this.initialDelay = initialDelay;
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
        long delay = initialDelay * fib(attempt + 1);
        return Math.min(delay, maxDelay);
    }

    /**
    * 重置内部尝试次数计数器。
    */
    public void reset() {
        attempt.set(0);
    }

    /**
     * Fib
     * @param n 方法入参 n
     * @return 结果数值
     */
    private static long fib(int n) {
        if (n <= 1) {
            return n;
        }
        long a = 0;
        long b = 1;
        for (int i = 2; i <= n; i++) {
            long c = a + b;
            a = b;
            b = c;
        }
        return b;
    }
}
