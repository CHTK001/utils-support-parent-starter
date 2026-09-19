package com.chua.enhance.support.network.server.filter.security;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IP 限流过滤器，基于令牌桶算法按 IP 进行精准限流。
 *
 * <p>每个 IP 独立维护一个令牌桶，默认容量 50 个令牌，每秒补充 10 个令牌。
 * 令牌耗尽时返回 429。
 *
 * <h2>配置参数</h2>
 * <ul>
 *   <li>{@code ipRateLimit.bucketCapacity} — 令牌桶容量，默认 50</li>
 *   <li>{@code ipRateLimit.refillRate} — 每秒补充令牌数，默认 10</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/16
 */
public class IpRateLimitServerFilter implements ServerFilter {

    /**
     * 默认令牌桶容量
     */
    private static final int DEFAULT_BUCKET_CAPACITY = 50;
    /**
     * 默认每秒补充令牌数
     */
    private static final int DEFAULT_REFILL_RATE = 10;

    /** 存储桶容量 */
    private int bucketCapacity = DEFAULT_BUCKET_CAPACITY;
    /** Refill比率 */
    private int refillRate = DEFAULT_REFILL_RATE;

    /**
     * IP → 令牌桶
     */
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Override
    /** 初始化 */
    public void init(ServerFilterConfig config) throws Exception {
        String capacity = config.getInitParameter("ipRateLimit.bucketCapacity");
        if (capacity != null && !capacity.isEmpty()) {
            this.bucketCapacity = Integer.parseInt(capacity);
        }
        String rate = config.getInitParameter("ipRateLimit.refillRate");
        if (rate != null && !rate.isEmpty()) {
            this.refillRate = Integer.parseInt(rate);
        }
    }

    @Override
    /** 执行过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        String ip = resolveClientIp(request);
        TokenBucket bucket = buckets.computeIfAbsent(ip,
                k -> new TokenBucket(bucketCapacity, refillRate));
        if (!bucket.tryConsume()) {
            response.end(429, "{\"error\":\"Too Many Requests\",\"message\":\"IP 请求频率过高\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    /** 获取订单 */
    public int getOrder() {
        return 25;
    }

    @Override
    /** 获取过滤标识 */
    public String getFilterId() {
        return "IpRateLimitServerFilter";
    }

    /**
     * 解析客户端ip
     *
     * @param request 请求
     * @return resolve客户端ip的结果
     */
    private String resolveClientIp(ServerRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            int idx = forwarded.indexOf(',');
            return idx > 0 ? forwarded.substring(0, idx).trim() : forwarded.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        return request.getRemoteAddress();
    }

    /**
     * 令牌桶实现，支持按时间补充令牌。
     * @author CH
     * @since 4.0.0
     */
    private static class TokenBucket {
        /** 容量 */
        private final int capacity;
        /** Refill比率PERMS */
        private final double refillRatePerMs;
        /** 令牌 */
        private final AtomicLong tokens;
        /** 最后一个refill时间 */
        private volatile long lastRefillTime;

        TokenBucket(int capacity, int refillRatePerSecond) {
            this.capacity = capacity;
            this.refillRatePerMs = (double) refillRatePerSecond / 1000.0;
            this.tokens = new AtomicLong(capacity);
            this.lastRefillTime = System.currentTimeMillis();
        }

        /**
         * 尝试consume
         *
         * @return 尝试consume的结果
         */
        synchronized boolean tryConsume() {
            refill();
            long current = tokens.get();
            if (current > 0) {
                tokens.decrementAndGet();
                return true;
            }
            return false;
        }

        /** Refill */
        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;
            if (elapsed <= 0) {
                return;
            }
            long newTokens = (long) (elapsed * refillRatePerMs);
            if (newTokens > 0) {
                tokens.updateAndGet(current -> Math.min(capacity, current + newTokens));
                lastRefillTime = now;
            }
        }
    }
}
