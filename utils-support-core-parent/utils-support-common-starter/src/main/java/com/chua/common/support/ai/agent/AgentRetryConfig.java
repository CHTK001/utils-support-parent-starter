package com.chua.common.support.ai.agent;

import lombok.Builder;
import lombok.Data;

/**
 * Agent 重试配置。
 * <p>
 * 控制 Agent 执行失败时的重试行为，包括最大重试次数和退避策略。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class AgentRetryConfig {

    /**
     * 最大重试次数。
     * <ul>
     *   <li>-1 = 无限重试</li>
     *   <li>0 = 不重试（默认）</li>
     *   <li>N = 重试 N 次</li>
     * </ul>
     */
    @Builder.Default
    /**
     * 最大重试次数
     */
    private int maxRetries = 0;

    /**
     * 退避策略。
     * <ul>
     *   <li>FIXED     — 固定间隔，每次等待相同时间</li>
     *   <li>LINEAR    — 线性递增，第 N 次等待 N × baseDelay</li>
     *   <li>EXPONENTIAL — 指数退避，第 N 次等待 baseDelay × 2^N</li>
     * </ul>
     */
    @Builder.Default
    /** Backoff策略 */
    private BackoffStrategy backoffStrategy = BackoffStrategy.EXPONENTIAL;

    /**
     * 基础延迟（毫秒）。
     * <p>退避计算的基准值。</p>
     */
    @Builder.Default
    /** Basedelay毫秒 */
    private long baseDelayMillis = 1000;

    /**
     * 最大延迟（毫秒）。
     * <p>退避等待的上限，防止等待时间过长。</p>
     */
    @Builder.Default
    /** 最大值delay毫秒 */
    private long maxDelayMillis = 30000;

    /**
     * 重试条件。
     * <p>返回 true 表示应重试。默认对所有异常重试。</p>
     */
    private RetryPredicate retryPredicate;

    /**
     * @author CH
     * 退避策略枚举。
     */
    public enum BackoffStrategy {
        /** 固定间隔 */
        FIXED,
        /** 线性递增 */
        LINEAR,
        /** 指数退避 */
        EXPONENTIAL
    }

    /**
     * 重试条件函数式接口。
     */
    @FunctionalInterface
    public interface RetryPredicate {
        /**
         * 判断是否应重试。
         *
         * @param exception 异常
         * @return true 表示应重试
         */
        boolean shouldRetry(Throwable exception);
    }

    /**
     * 计算第 attempt 次重试的等待时间（毫秒）。
     *
     * @param attempt 重试次数（从 0 开始）
     * @return 等待时间
     */
    public long calculateDelayMillis(int attempt) {
        long delay;
        switch (backoffStrategy) {
            case FIXED:
                delay = baseDelayMillis;
                break;
            case LINEAR:
                delay = baseDelayMillis * (attempt + 1);
                break;
            case EXPONENTIAL:
            default:
                delay = baseDelayMillis * (1L << attempt);
                break;
        }
        return Math.min(delay, maxDelayMillis);
    }
}
