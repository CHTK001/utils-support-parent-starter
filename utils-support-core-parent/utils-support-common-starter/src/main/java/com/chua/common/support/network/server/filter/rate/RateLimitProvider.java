package com.chua.common.support.network.server.filter.rate;

import com.chua.common.support.network.server.request.ServerRequest;
import org.jspecify.annotations.NullUnmarked;

/**
 * 限流提供者 SPI 接口。
 *
 * <p>定义限流策略的统一抽象，具体实现通过 SPI 发现：</p>
 * <ul>
 *   <li>{@code guava} — 基于 Guava RateLimiter 的令牌桶算法</li>
 *   <li>{@code sliding-window} — 滑动窗口算法</li>
 *   <li>{@code fixed-window} — 固定窗口算法</li>
 *   <li>{@code redis} — 分布式 Redis 限流</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 通过 SPI 获取限流提供者
 * RateLimitProvider provider = ServiceProvider.of(RateLimitProvider.class).getExtension("guava");
 *
 * // 创建限流器
 * RateLimiter limiter = provider.create(100.0); // QPS=100
 *
 * // 尝试获取许可
 * if (limiter.tryAcquire("api:/users")) {
 *     // 处理请求
 * }
 * }</pre>
 *
 * @author CH
 * @since 2026/07/18
 */
@NullUnmarked
public interface RateLimitProvider {

    /**
     * 创建限流器。
     *
     * @param qps 每秒最大请求数
     * @return 限流器实例
     */
    RateLimiter create(double qps);

    /**
     * 创建按 key 分组的限流器。
     *
     * @param qps 每秒最大请求数
     * @return 限流器实例
     */
    default RateLimiter createPerKey(double qps) {
        return create(qps);
    }

    /**
     * 获取实现名称。
     *
     * @return 实现名称
     */
    String getName();

    /**
     * 限流器接口。
     */
    interface RateLimiter {
        /**
         * 尝试获取许可（非阻塞）。
         *
         * @param key 限流 key（如 IP、路径）
         * @return 是否获取成功
         */
        boolean tryAcquire(String key);

        /**
         * 尝试获取许可（带超时）。
         *
         * @param key     限流 key
         * @param timeout 超时毫秒数
         * @return 是否获取成功
         */
        boolean tryAcquire(String key, long timeout);

        /**
         * 获取当前剩余配额。
         *
         * @param key 限流 key
         * @return 剩余请求数
         */
        long getRemaining(String key);

        /**
         * 获取限流器容量。
         *
         * @return QPS
         */
        double getCapacity();
    }
}
