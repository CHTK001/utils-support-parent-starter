package com.chua.common.support.task.retry;

import java.util.function.Predicate;

/**
 * 重试配置
 *
 * <p>定义重试行为的各项参数，支持链式调用风格。
 *
 * @author CH
 * @since 1.0.0
 */
public class RetryConfig {
    /**
     * 最大重试次数
     */
    private int maxRetries = 3;
    /**
     * 延迟（毫秒）
     */
    private long delay = 1000;
    private double multiplier = 2.0;
    private BackoffStrategy backoffStrategy = BackoffStrategy.FIXED;
    private Predicate<Throwable> retryOnException;
    private RetryListenerCallback retryListener = (attempt, cause) -> {};

    public enum BackoffStrategy {
        FIXED,
        EXPONENTIAL,
        FIBONACCI
    }

    public interface RetryListenerCallback {
        void onRetry(int attemptNumber, Throwable cause);
    }

    public int getMaxRetries() { return maxRetries; }
    public RetryConfig setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
    public long getDelay() { return delay; }
    public RetryConfig setDelay(long delay) { this.delay = delay; return this; }
    public double getMultiplier() { return multiplier; }
    public RetryConfig setMultiplier(double multiplier) { this.multiplier = multiplier; return this; }
    public BackoffStrategy getBackoffStrategy() { return backoffStrategy; }
    public RetryConfig setBackoffStrategy(BackoffStrategy backoffStrategy) { this.backoffStrategy = backoffStrategy; return this; }
    public Predicate<Throwable> getRetryOnException() { return retryOnException; }
    public RetryConfig setRetryOnException(Predicate<Throwable> retryOnException) { this.retryOnException = retryOnException; return this; }
    public RetryListenerCallback getRetryListener() { return retryListener; }
    public RetryConfig setRetryListener(RetryListenerCallback retryListener) { this.retryListener = retryListener; return this; }
}
