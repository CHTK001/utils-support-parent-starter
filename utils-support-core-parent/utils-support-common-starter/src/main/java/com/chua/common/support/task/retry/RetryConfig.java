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
    /** 倍数 */
    private double multiplier = 2.0;
    /** Backoff策略 */
    private BackoffStrategy backoffStrategy = BackoffStrategy.FIXED;
    /** 重试ON异常 */
    private Predicate<Throwable> retryOnException;
    /** 重试监听器 */
    private RetryListenerCallback retryListener = (attempt, cause) -> {};

    public enum BackoffStrategy {
        FIXED,
        EXPONENTIAL,
        FIBONACCI
    }

    public interface RetryListenerCallback {
        void onRetry(int attemptNumber, Throwable cause);
    }

    /** 获取最大值Retries */
    public int getMaxRetries() { return maxRetries; }
    /** 设置最大值Retries */
    public RetryConfig setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
    /** 获取Delay */
    public long getDelay() { return delay; }
    /** 设置Delay */
    public RetryConfig setDelay(long delay) { this.delay = delay; return this; }
    /** 获取Multiplier */
    public double getMultiplier() { return multiplier; }
    /** 设置Multiplier */
    public RetryConfig setMultiplier(double multiplier) { this.multiplier = multiplier; return this; }
    /** 获取BackoffStrategy */
    public BackoffStrategy getBackoffStrategy() { return backoffStrategy; }
    /** 设置BackoffStrategy */
    public RetryConfig setBackoffStrategy(BackoffStrategy backoffStrategy) { this.backoffStrategy = backoffStrategy; return this; }
    /** 获取RetryOnException */
    public Predicate<Throwable> getRetryOnException() { return retryOnException; }
    /** 设置RetryOnException */
    public RetryConfig setRetryOnException(Predicate<Throwable> retryOnException) { this.retryOnException = retryOnException; return this; }
    /** 获取RetryListener */
    public RetryListenerCallback getRetryListener() { return retryListener; }
    /** 设置RetryListener */
    public RetryConfig setRetryListener(RetryListenerCallback retryListener) { this.retryListener = retryListener; return this; }
}
