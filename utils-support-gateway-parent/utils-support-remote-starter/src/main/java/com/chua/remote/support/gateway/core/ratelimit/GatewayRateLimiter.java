package com.chua.remote.support.gateway.core.ratelimit;

import com.google.common.util.concurrent.RateLimiter;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 网关限流器
 * <p>
 * 基于 Guava RateLimiter 实现令牌桶限流算法，支持：
 * <ul>
 *   <li>令牌桶限流（每秒令牌数）</li>
 *   <li>最大并发数控制（信号量模式）</li>
 * </ul>
 * 限流参数可在运行时动态调整。
 *
 * @author CH
 * @since 2025
 */
@Slf4j
public class GatewayRateLimiter {
    /** Guava 令牌桶限流器 */
    private volatile RateLimiter rateLimiter;
    /** 最大并发数 */
    @Getter private volatile int maxConcurrent;
    /** 当前并发数 */
    private int concurrent;

    /**
     * 构造限流器
     *
     * @param tokensPerSecond 每秒允许的令牌数（QPS 上限）
     * @param maxConcurrent   最大并发请求数
     */
    public GatewayRateLimiter(double tokensPerSecond, int maxConcurrent) {
        this.rateLimiter = RateLimiter.create(tokensPerSecond);
        this.maxConcurrent = maxConcurrent;
    }

    /** 运行时更新限流参数 */
    public void setRateLimit(double tokensPerSecond, int burstCapacity) {
        this.rateLimiter = RateLimiter.create(tokensPerSecond);
        this.maxConcurrent = burstCapacity;
        log.info("[RateLimiter] 参数更新: tps={} burst={}", tokensPerSecond, burstCapacity);
    }

    /**
     * 尝试获取一个令牌（非阻塞）
     * <p>
     * 仅检查令牌桶中是否有可用令牌，不等待。
     *
     * @return 获取成功返回 true
     */
    public boolean tryAcquire() { return rateLimiter.tryAcquire(); }

    /**
     * 尝试进入并发执行区（非阻塞）
     * <p>
     * 如果当前并发数未达到上限，则递增并发计数并返回 true；
     * 否则返回 false，调用方应拒绝请求。
     *
     * @return 成功进入返回 true
     */
    public synchronized boolean tryEnter() {
        if (concurrent >= maxConcurrent) { return false; }
        concurrent++;
        return true;
    }

    /**
     * 释放一个并发槽位
     * <p>
     * 在请求处理完成后调用，与 {@link #tryEnter()} 配对使用。
     */
    public synchronized void release() {
        if (concurrent > 0) {
            concurrent--;
        }
    }
}
