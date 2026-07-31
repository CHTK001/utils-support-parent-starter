package com.chua.common.support.task.retry;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * JDK 默认重试提供者实现
 *
 * <p>基于纯 JDK 循环等待机制实现的重试策略，不依赖任何第三方重试库。
 * 支持三种退避策略：固定延迟、指数退避、斐波那契退避。
 *
 * <p>工作流程：
 * <ol>
 *   <li>执行任务，如果成功则直接返回结果</li>
 *   <li>如果抛出异常，检查是否达到最大重试次数</li>
 *   <li>如果未达到上限，根据退避策略计算等待时间并休眠</li>
 *   <li>休眠结束后重新执行任务，重复步骤 1-3</li>
 *   <li>超过最大重试次数后，抛出最后一次捕获的异常</li>
 * </ol>
 *
 * @author CH
 * @since 1.0.0
 */
public class JdkRetryProvider extends AbstractRetryProvider {

    /**
     * 默认重试配置
     */
    private final RetryConfig defaultConfig;

    /**
     * 创建使用默认配置的 JDK 重试提供者
     */
    public JdkRetryProvider() {
        this(new RetryConfig());
    }

    /**
     * 创建使用指定默认配置的 JDK 重试提供者
     *
     * @param defaultConfig 默认重试配置
     */
    public JdkRetryProvider(RetryConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    /**
     * 使用默认配置执行带重试能力的任务
     *
     * @param <T>  返回值类型
     * @param task 待执行的任务
     * @return 任务执行结果
     * @throws Exception 所有重试均失败后抛出最后一次异常
     */
    @Override
    protected <T> T doExecute(Callable<T> task, RetryConfig config) throws Exception {
        Exception lastException = null;
        for (int i = 0; i <= config.getMaxRetries(); i++) {
            try {
                return task.call();
            } catch (Exception e) {
                lastException = e;
                if (config.getRetryOnException() != null && !config.getRetryOnException().test(e)) {
                    throw e;
                }
                if (i >= config.getMaxRetries()) {
                    throw e;
                }
                config.getRetryListener().onRetry(i + 1, e);
                long delay = computeDelay(i, config);
                TimeUnit.MILLISECONDS.sleep(delay);
            }
        }
        throw new RetryException(config.getMaxRetries(), lastException);
    }

    /**
     * 根据退避策略计算下次重试前的等待时间
     *
     * @param attempt 当前已重试次数
     * @param config  重试配置
     * @return 等待时间（毫秒）
     */
    private long computeDelay(int attempt, RetryConfig config) {
        switch (config.getBackoffStrategy()) {
            case EXPONENTIAL:
                return (long) (config.getDelay() * Math.pow(config.getMultiplier(), attempt));
            case FIBONACCI:
                return config.getDelay() * fib(attempt + 1);
            case FIXED:
            default:
                return config.getDelay();
        }
    }

    /**
     * 计算斐波那契数列第 n 项
     */
    private static long fib(int n) {
        if (n <= 1) {
            return n;
        }
        long a = 0, b = 1;
        for (int i = 2; i <= n; i++) {
            long c = a + b;
            a = b;
            b = c;
        }
        return b;
    }
}
