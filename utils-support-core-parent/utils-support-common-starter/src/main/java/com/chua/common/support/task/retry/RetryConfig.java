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
    /** 退避策略 */
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

    /**
    * 获取最大值Retries
    *
    * @return 获取最大重试的结果
    */
    public int getMaxRetries() { return maxRetries; }
    /**
    * 设置最大值Retries
    *
    * @param maxRetries 最大重试
    * @return 设置最大重试的结果
    */
    public RetryConfig setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }
    /**
    * 获取延迟
    *
    * @return 获取延迟的结果
    */
    public long getDelay() { return delay; }
    /**
    * 设置延迟
    *
    * @param delay 延迟
    * @return 设置延迟的结果
    */
    public RetryConfig setDelay(long delay) {
        this.delay = delay;
        return this;
    }
    /**
    * 获取Multiplier
    *
    * @return 获取multiplier的结果
    */
    public double getMultiplier() { return multiplier; }
    /**
    * 设置Multiplier
    *
    * @param multiplier multiplier
    * @return 设置multiplier的结果
    */
    public RetryConfig setMultiplier(double multiplier) {
        this.multiplier = multiplier;
        return this;
    }
    /**
    * 获取退避strategy
    *
    * @return 获取退避strategy的结果
    */
    public BackoffStrategy getBackoffStrategy() { return backoffStrategy; }
    /**
    * 设置退避strategy
    *
    * @param backoffStrategy 退避strategy
    * @return 设置退避strategy的结果
    */
    public RetryConfig setBackoffStrategy(BackoffStrategy backoffStrategy) {
        this.backoffStrategy = backoffStrategy;
        return this;
    }
    /**
    * 获取重试on异常
    *
    * @return 获取重试on异常的结果
    */
    public Predicate<Throwable> getRetryOnException() { return retryOnException; }
    /**
    * 设置重试on异常
    *
    * @param retryOnException 重试on异常
    * @return 设置重试on异常的结果
    */
    public RetryConfig setRetryOnException(Predicate<Throwable> retryOnException) {
        this.retryOnException = retryOnException;
        return this;
    }
    /**
    * 获取重试监听器
    *
    * @return 获取重试监听器的结果
    */
    public RetryListenerCallback getRetryListener() { return retryListener; }
    /**
    * 设置重试监听器
    *
    * @param retryListener 重试监听器
    * @return 设置重试监听器的结果
    */
    public RetryConfig setRetryListener(RetryListenerCallback retryListener) {
        this.retryListener = retryListener;
        return this;
    }
}
