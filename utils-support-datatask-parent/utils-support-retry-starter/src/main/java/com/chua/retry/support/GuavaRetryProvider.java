package com.chua.retry.support;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.retry.AbstractRetryProvider;
import com.chua.common.support.task.retry.RetryConfig;
import com.github.rholder.retry.Retryer;
import com.github.rholder.retry.RetryerBuilder;
import com.github.rholder.retry.RetryException;
import com.github.rholder.retry.StopStrategies;
import com.github.rholder.retry.WaitStrategies;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Guava 重试 的重试提供者实现
 *
 * <p>使用 Guava Retrying 库提供声明式重试能力。Guava Retrying 是一个轻量级的
 * 重试框架，支持灵活的停止策略、等待策略和异常监听。
 *
 * <p>与默认 {@link com.chua.common.support.task.retry.JdkRetryProvider} 相比，
 * Guava 重试 提供更丰富的策略组合：
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
    /**
     * 执行执行
    */
    protected <T> T doExecute(Callable<T> task, RetryConfig config) throws Exception {
        var builder = RetryerBuilder.<T>newBuilder()
                .withStopStrategy(StopStrategies.stopAfterAttempt(config.getMaxRetries() + 1))
                .withWaitStrategy(toWaitStrategy(config));
        var filter = config.getRetryOnException();
        if (filter == null) {
            builder.retryIfException();
        } else {
            // 与 JdkRetryProvider 一致：过滤器判定为不可重试的异常直接抛出，不再消耗重试次数
            builder.retryIfException(filter::test);
        }

        if (config.getRetryListener() != null) {
            builder.withRetryListener(new com.github.rholder.retry.RetryListener() {
                @Override
                /**
                 * on重试
                */
                public <V> void onRetry(com.github.rholder.retry.Attempt<V> attempt) {
                    if (attempt.hasException()) {
                        config.getRetryListener().onRetry((int) attempt.getAttemptNumber(), attempt.getExceptionCause());
                    }
                }
            });
        }

        Retryer<T> retryer = builder.build();
        try {
            return retryer.call(task);
        } catch (ExecutionException e) {
            throw causeOr(e.getCause(), e);
        } catch (RetryException e) {
            var last = e.getLastFailedAttempt();
            throw causeOr(last != null && last.hasException() ? last.getExceptionCause() : e.getCause(), e);
        }
    }

    /**
     * 还原为任务本身抛出的异常，取不到时退回原异常。
     *
     * @param candidate 待还原的异常因，可为 空
     * @param fallback  兜底异常
     * @return 应抛出的异常
     */
    private static Exception causeOr(Throwable candidate, Exception fallback) {
        return candidate instanceof Exception exception ? exception : fallback;
    }

    /**
     * 转为waitstrategy
     *
     * @param config 配置
     * @return 转为waitstrategy的结果
     */
    private static com.github.rholder.retry.WaitStrategy toWaitStrategy(RetryConfig config) {
        return switch (config.getBackoffStrategy()) {
            case FIXED -> WaitStrategies.fixedWait(config.getDelay(), TimeUnit.MILLISECONDS);
            case EXPONENTIAL -> exponentialWait(config);
            case FIBONACCI -> fibonacciWait(config);
        };
    }

    /**
     * 指数退避：与 {@link com.chua.common.support.task.retry.JdkRetryProvider} 同式，
     * 第 n 次重试前等待 {@code delay * multiplier^(n-1)} 毫秒。
     *
     * <p>库自带的 {@code exponentialWait(long, TimeUnit)} 把入参当作等待上限、增长基数写死为
     * 1 毫秒，且没有可用的倍率入口，无法满足 {@link RetryConfig#getMultiplier()} 语义，故自行实现。</p>
     *
     * @param config 重试配置
     * @return 指数退避等待策略
     */
    private static com.github.rholder.retry.WaitStrategy exponentialWait(RetryConfig config) {
        long delay = config.getDelay();
        double multiplier = config.getMultiplier();
        return failedAttempt -> {
            long round = failedAttempt.getAttemptNumber() - 1;
            if (round <= 0) {
                return delay;
            }
            double wait = delay * Math.pow(multiplier, round);
            return wait >= Long.MAX_VALUE || wait < 0 ? Long.MAX_VALUE : (long) wait;
        };
    }

    /**
     * 斐波那契退避：第 n 次重试前等待 {@code delay * fib(n)} 毫秒。
     *
     * <p>同指数退避，库自带的 {@code fibonacciWait(long, TimeUnit)} 把入参当作等待上限，故自行实现。</p>
     *
     * @param config 重试配置
     * @return 斐波那契退避等待策略
     */
    private static com.github.rholder.retry.WaitStrategy fibonacciWait(RetryConfig config) {
        long delay = config.getDelay();
        return failedAttempt -> {
            long factor = fib(failedAttempt.getAttemptNumber());
            if (factor <= 0) {
                return delay;
            }
            return delay > Long.MAX_VALUE / factor ? Long.MAX_VALUE : delay * factor;
        };
    }

    /**
     * 计算斐波那契数列第 n 项（与参考实现同定义：fib(0)=0、fib(1)=1）。
     *
     * @param n 序号，非负
     * @return 第 n 项；超出 long 范围时返回 {@link Long#MAX_VALUE}
     */
    private static long fib(long n) {
        if (n <= 1) {
            return Math.max(n, 0);
        }
        long a = 0;
        long b = 1;
        for (int i = 2; i <= n; i++) {
            long next = a + b;
            if (next < 0) {
                return Long.MAX_VALUE;
            }
            a = b;
            b = next;
        }
        return b;
    }
}
