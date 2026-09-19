package com.chua.common.support.concurrent.circuitbreaker.provider;

import com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于内存状态机的熔断器默认实现。
 *
 * <p>三态流转：CLOSED → OPEN（失败达阈值）→ HALF_OPEN（等待超时）→ CLOSED（成功达阈值）</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("default")
public class InMemoryCircuitBreakerProvider implements CircuitBreakerProvider {

    /**
     * 熔断器状态
     */
    private enum State {
        CLOSED, OPEN, HALF_OPEN
    }

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
     * 当前状态
     */
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    /**
     * 创建 InMemoryCircuitBreakerProvider 实例
     * @param name name
     * @param failureThreshold int
     * @param failureThreshold int
     * @param waitDuration long
     * @param successThreshold 方法入参 successThreshold
     */
    public InMemoryCircuitBreakerProvider(String name, int failureThreshold, int successThreshold, long waitDuration) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
        this.waitDuration = waitDuration;
    }

    @Override
    /** Try获取 */
    public boolean tryAcquire() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            long elapsed = System.currentTimeMillis() - openTimestamp.get();
            if (elapsed >= waitDuration) {
                // 等待超时，进入半开状态
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    successCount.set(0);
                }
                return true;
            }
            return false;
        }
        // HALF_OPEN：放行试探请求
        return true;
    }

    @Override
    /** RecordSuccess */
    public void recordSuccess() {
        State current = state.get();
        if (current == State.OPEN) {
            return;
        }
        failureCount.set(0);
        if (current == State.HALF_OPEN) {
            // 半开状态：成功计数，达阈值后关闭
            if (successCount.incrementAndGet() >= successThreshold) {
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    successCount.set(0);
                    failureCount.set(0);
                }
            }
        }
    }

    @Override
    /** RecordFailure */
    public void recordFailure() {
        State current = state.get();
        if (current == State.OPEN) {
            return;
        }
        if (current == State.HALF_OPEN) {
            // 半开状态：失败一次立即回到打开
            state.set(State.OPEN);
            openTimestamp.set(System.currentTimeMillis());
            failureCount.set(0);
            return;
        }
        // CLOSED：失败计数，达阈值后打开
        if (failureCount.incrementAndGet() >= failureThreshold) {
            state.set(State.OPEN);
            openTimestamp.set(System.currentTimeMillis());
        }
    }

    @Override
    /** 重置 */
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        successCount.set(0);
        openTimestamp.set(0);
    }

    @Override
    /** 是否打开 */
    public boolean isOpen() {
        return state.get() == State.OPEN;
    }

    @Override
    /** 获取Name */
    public String getName() {
        return name;
    }
}
