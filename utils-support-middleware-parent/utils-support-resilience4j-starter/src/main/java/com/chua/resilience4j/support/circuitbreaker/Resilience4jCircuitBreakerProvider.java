package com.chua.resilience4j.support.circuitbreaker;

import com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerProvider;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Resilience4j 的熔断器实现。
 *
 * <p>SPI 名称为 {@code "default"}，order=100 优先级高于内存实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "default", order = 100)
@ConditionalOnClass("io.github.resilience4j.circuitbreaker.CircuitBreaker")
public class Resilience4jCircuitBreakerProvider implements CircuitBreakerProvider {

    /** Circuitbreaker */
    private final CircuitBreaker circuitBreaker;

    /**
     * 调用开始时间（纳秒），由 {@link #tryAcquire()} 记录。
     */
    private final AtomicLong callStartNs = new AtomicLong(-1);

    /**
     * 创建 Resilience4jCircuitBreakerProvider 实例
     * @param name name
     * @param int int
     * @param int int
     * @param long long
     */
    public Resilience4jCircuitBreakerProvider(String name, int failureThreshold, int successThreshold, long waitDuration) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold((float) failureThreshold / 100)
                .slidingWindowSize(failureThreshold * 2)
                .permittedNumberOfCallsInHalfOpenState(successThreshold)
                .waitDurationInOpenState(Duration.ofMillis(waitDuration))
                .build();
        this.circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker(name);
    }

    @Override
    /** Try获取 */
    public boolean tryAcquire() {
        boolean acquired = circuitBreaker.tryAcquirePermission();
        if (acquired) {
            callStartNs.set(System.nanoTime());
        }
        return acquired;
    }

    @Override
    /** RecordSuccess */
    public void recordSuccess() {
        long start = callStartNs.getAndSet(-1);
        if (start < 0) {
            return;
        }
        circuitBreaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }

    @Override
    /** RecordFailure */
    public void recordFailure() {
        long start = callStartNs.getAndSet(-1);
        if (start < 0) {
            return;
        }
        circuitBreaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS,
                new RuntimeException("circuit breaker failure"));
    }

    @Override
    /** 重置 */
    public void reset() {
        circuitBreaker.reset();
    }

    @Override
    /** 是否打开 */
    public boolean isOpen() {
        return circuitBreaker.getState() == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN;
    }

    @Override
    /** 获取Name */
    public String getName() {
        return circuitBreaker.getName();
    }
}