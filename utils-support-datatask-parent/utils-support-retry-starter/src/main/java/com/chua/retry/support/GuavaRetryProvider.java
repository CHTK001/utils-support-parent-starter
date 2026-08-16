package com.chua.retry.support;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.retry.AbstractRetryProvider;
import com.chua.common.support.task.retry.RetryConfig;
import com.github.rholder.retry.Retryer;
import com.github.rholder.retry.RetryerBuilder;
import com.github.rholder.retry.StopStrategies;
import com.github.rholder.retry.WaitStrategies;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Guava Retrying 的重试提供者实现
 *
 * <p>使用 Guava Retrying 库提供声明式重试能力。Guava Retrying 是一个轻量级的
 * 重试框架，支持灵活的停止策略、等待策略和异常监听。
 *
 * <p>与默认 {@link com.chua.common.support.task.retry.JdkRetryProvider} 相比，
 * Guava Retrying 提供更丰富的策略组合：
 * <ul>
 *   <li><strong>停止策略</strong>：最大重试次数、超时时间、永不停止</li>
 *   <li><strong>等待策略</strong>：固定等待、指数退避、斐波那契退避、随机等待</li>
 *   <li><strong>异常监听</strong>：重试回调监听器</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("guava-retry")
public class GuavaRetryProvider extends AbstractRetryProvider {

    @Override
    protected <T> T doExecute(Callable<T> task, RetryConfig config) throws Exception {
        var builder = RetryerBuilder.<T>newBuilder()
                .retryIfException()
                .withStopStrategy(StopStrategies.stopAfterAttempt(config.getMaxRetries() + 1))
                .withWaitStrategy(toWaitStrategy(config));

        if (config.getRetryListener() != null) {
            builder.withRetryListener(new com.github.rholder.retry.RetryListener() {
                @Override
                public <V> void onRetry(com.github.rholder.retry.Attempt<V> attempt) {
                    if (attempt.hasException()) {
                        config.getRetryListener().onRetry((int) attempt.getAttemptNumber(), attempt.getExceptionCause());
                    }
                }
            });
        }

        Retryer<T> retryer = builder.build();
        return retryer.call(task);
    }

    private static com.github.rholder.retry.WaitStrategy toWaitStrategy(RetryConfig config) {
        return switch (config.getBackoffStrategy()) {
            case FIXED -> WaitStrategies.fixedWait(config.getDelay(), TimeUnit.MILLISECONDS);
            case EXPONENTIAL -> WaitStrategies.exponentialWait(
                    (long) (config.getDelay() * config.getMultiplier()),
                    TimeUnit.MILLISECONDS);
            case FIBONACCI -> WaitStrategies.fibonacciWait(config.getDelay(), TimeUnit.MILLISECONDS);
        };
    }
}
