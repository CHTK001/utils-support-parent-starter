package com.chua.common.support.concurrent.backoff;


/**
 * 避让器提供者 SPI 接口。
 *
 * <p>定义避让延迟计算的核心行为。通过 SPI 机制支持不同的避让策略
 * （如指数退避、固定延迟、斐波那契退避等）。</p>
 *
 * @author CH
 * @since 2026/07/24
 */
public interface BackoffProvider {

    /**
     * 计算下一次避让的等待时间（毫秒）。
     *
     * @param attempt 当前尝试次数（从 0 开始）
     * @return 等待时间（毫秒）
     */
    long nextDelay(int attempt);

    /**
     * 执行避让休眠。
     *
     * @param attempt 当前尝试次数（从 0 开始）
     */
    default void sleep(int attempt) {
        try {
            Thread.sleep(nextDelay(attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 重置内部状态。
     */
    default void reset() {
    }
}