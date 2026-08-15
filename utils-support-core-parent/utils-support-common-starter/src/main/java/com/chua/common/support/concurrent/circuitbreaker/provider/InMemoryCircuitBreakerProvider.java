package com.chua.common.support.concurrent.circuitbreaker.provider;

import com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于内存状态机的熔断器默认实现。
 *
 * <p>三种状态：</p>
 * <ul>
 *   <li>CLOSED（关闭）— 正常调用，失败计数</li>
 *   <li>OPEN（打开）— 拒绝调用，等待超时后进入半开</li>
 *   <li>HALF_OPEN（半开）— 允许少量调用试探，成功阈值达到后关闭</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("default")
public class InMemoryCircuitBreakerProvider implements CircuitBreakerProvider {

    /**
     * 熔断器名称
     */
    private final String name;

    /**
     * 失败阈值
     */
    private final int failureThreshold;

    /**
     * 成功阈值
     */
    private final int successThreshold;

    /**
     * 熔断等待时间（毫秒）
     */
    private final long waitDuration;

    /**
     * 当前连续失败次数
     */
    private final AtomicInteger failureCount = new AtomicInteger(0);

    /**
     * 当前连续成功次数（半开状态）
     */
    private final AtomicInteger successCount = new AtomicInteger(0);

    /**
     * 熔断打开的时间戳
     */
    private final AtomicLong openTimestamp = new AtomicLong(0);

    /**
     * 当前状态：true=打开，false=关闭
     */
    private volatile boolean open;

    public InMemoryCircuitBreakerProvider(String name, int failureThreshold, int successThreshold, long waitDuration) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
        this.waitDuration = waitDuration;
    }

    @Override
    public boolean tryAcquire() {
        if (!open) {
            return true;
        }
        long elapsed = System.currentTimeMillis() - openTimestamp.get();
        if (elapsed >= waitDuration) {
            open = false;
            successCount.set(0);
            return true;
        }
        return false;
    }

    @Override
    public void recordSuccess() {
        if (open) {
            return;
        }
        failureCount.set(0);
        if (successCount.incrementAndGet() >= successThreshold) {
            open = false;
            successCount.set(0);
            failureCount.set(0);
        }
    }

    @Override
    public void recordFailure() {
        if (open) {
            return;
        }
        if (failureCount.incrementAndGet() >= failureThreshold) {
            open = true;
            openTimestamp.set(System.currentTimeMillis());
        }
    }

    @Override
    public void reset() {
        open = false;
        failureCount.set(0);
        successCount.set(0);
        openTimestamp.set(0);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public String getName() {
        return name;
    }
}