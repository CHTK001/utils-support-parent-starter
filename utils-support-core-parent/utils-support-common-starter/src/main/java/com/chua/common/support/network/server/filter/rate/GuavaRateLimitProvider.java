package com.chua.common.support.network.server.filter.rate;

import com.chua.common.support.spi.annotations.Spi;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guava RateLimiter 实现的限流提供者。
 *
 * <p>基于令牌桶算法，支持平滑限流和突发流量处理。</p>
 *
 * @author CH
 * @since 2026/07/18
 */
@Spi("guava")
public class GuavaRateLimitProvider implements RateLimitProvider {

    /**
     * 创建
     */
    @Override
    public RateLimiter create(double qps) {
        return new GuavaRateLimiter(qps);
    }

    /**
     * 创建PerKey
     */
    @Override
    public RateLimiter createPerKey(double qps) {
        return new PerKeyGuavaRateLimiter(qps);
    }

    /**
     * 获取Name
     */
    @Override
    public String getName() {
        return "guava";
    }

    /**
     * 全局 Guava 限流器。
     */
    private static class GuavaRateLimiter implements RateLimiter {
        /**
         * Limiter
         */
        private final com.google.common.util.concurrent.RateLimiter limiter;
        /**
         * 容量
         */
        private final double capacity;

        /**
         * Guava Rate Limiter
         * @param qps 双精度数
         * @return 返回值
        */
        GuavaRateLimiter(double qps) {
            this.limiter = com.google.common.util.concurrent.RateLimiter.create(qps);
            this.capacity = qps;
        }

        /**
         * Try获取
         */
        @Override
        public boolean tryAcquire(String key) {
            return limiter.tryAcquire();
        }

        /**
         * Try获取
         */
        @Override
        public boolean tryAcquire(String key, long timeout) {
            return limiter.tryAcquire(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        /**
         * 获取Remaining
         */
        @Override
        public long getRemaining(String key) {
            return (long) capacity;
        }

        /**
         * 获取Capacity
         */
        @Override
        public double getCapacity() {
            return capacity;
        }

        /**
         * 预估下一次令牌可用毫秒数
         */
        @Override
        public long estimateNextAvailableMillis(String key) {
            // tryAcquire 失败说明存量令牌已耗尽，平滑限流器下一个令牌约在 1/qps 后成熟；
            // 向上取整保证调用方不会早于令牌成熟时刻唤醒
            return Math.max(1L, (long) Math.ceil(1000.0 / capacity));
        }
    }

    /**
     * 按 key 分组的 Guava 限流器。
     */
    private static class PerKeyGuavaRateLimiter implements RateLimiter {
        /**
         * QPS
         */
        private final double qps;
        /**
         * limiters
         */
        private final Map<String, com.google.common.util.concurrent.RateLimiter> limiters = new ConcurrentHashMap<>();

        /**
         * Per Key Guava Rate Limiter
         * @param qps 双精度数
         * @return 返回值
        */
        PerKeyGuavaRateLimiter(double qps) {
            this.qps = qps;
        }

        /**
         * Try获取
         */
        @Override
        public boolean tryAcquire(String key) {
            return getOrCreate(key).tryAcquire();
        }

        /**
         * Try获取
         */
        @Override
        public boolean tryAcquire(String key, long timeout) {
            return getOrCreate(key).tryAcquire(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        /**
         * 获取Remaining
         */
        @Override
        public long getRemaining(String key) {
            return (long) qps;
        }

        /**
         * 获取Capacity
         */
        @Override
        public double getCapacity() {
            return qps;
        }

        /**
         * 预估下一次令牌可用毫秒数
         */
        @Override
        public long estimateNextAvailableMillis(String key) {
            // 各 key 的限流器同为 1/qps 间隔，向上取整防早醒
            return Math.max(1L, (long) Math.ceil(1000.0 / qps));
        }

        /**
         * 获取Or创建
         * @param key 字符串
         * @return 返回值
        */
        private com.google.common.util.concurrent.RateLimiter getOrCreate(String key) {
            return limiters.computeIfAbsent(key, k -> com.google.common.util.concurrent.RateLimiter.create(qps));
        }
    }
}
