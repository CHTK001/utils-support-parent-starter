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

    @Override
    /** 创建 */
    public RateLimiter create(double qps) {
        return new GuavaRateLimiter(qps);
    }

    @Override
    /** 创建PerKey */
    public RateLimiter createPerKey(double qps) {
        return new PerKeyGuavaRateLimiter(qps);
    }

    @Override
    /** 获取Name */
    public String getName() {
        return "guava";
    }

    /**
    * 全局 Guava 限流器。
     */
    private static class GuavaRateLimiter implements RateLimiter {
        /** Limiter */
        private final com.google.common.util.concurrent.RateLimiter limiter;
        /**
        * 容量
         */
        private final double capacity;

        GuavaRateLimiter(double qps) {
            this.limiter = com.google.common.util.concurrent.RateLimiter.create(qps);
            this.capacity = qps;
        }

        @Override
        /** Try获取 */
        public boolean tryAcquire(String key) {
            return limiter.tryAcquire();
        }

        @Override
        /** Try获取 */
        public boolean tryAcquire(String key, long timeout) {
            return limiter.tryAcquire(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        @Override
        /** 获取Remaining */
        public long getRemaining(String key) {
            return (long) capacity;
        }

        @Override
        /** 获取Capacity */
        public double getCapacity() {
            return capacity;
        }
    }

    /**
    * 按 key 分组的 Guava 限流器。
     */
    private static class PerKeyGuavaRateLimiter implements RateLimiter {
        /** QPS */
        private final double qps;
        /** limiters */
        private final Map<String, com.google.common.util.concurrent.RateLimiter> limiters = new ConcurrentHashMap<>();

        PerKeyGuavaRateLimiter(double qps) {
            this.qps = qps;
        }

        @Override
        /** Try获取 */
        public boolean tryAcquire(String key) {
            return getOrCreate(key).tryAcquire();
        }

        @Override
        /** Try获取 */
        public boolean tryAcquire(String key, long timeout) {
            return getOrCreate(key).tryAcquire(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        @Override
        /** 获取Remaining */
        public long getRemaining(String key) {
            return (long) qps;
        }

        @Override
        /** 获取Capacity */
        public double getCapacity() {
            return qps;
        }

        /** 获取Or创建 */
        private com.google.common.util.concurrent.RateLimiter getOrCreate(String key) {
            return limiters.computeIfAbsent(key, k -> com.google.common.util.concurrent.RateLimiter.create(qps));
        }
    }
}
