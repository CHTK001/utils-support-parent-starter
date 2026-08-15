package com.chua.resilience4j.support.circuitbreaker;

import com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerProvider;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.time.Duration;

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

    private final CircuitBreaker circuitBreaker;

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
    public boolean tryAcquire() {
        return circuitBreaker.tryAcquirePermission();
    }

    @Override
    public void recordSuccess() {
        circuitBreaker.onResult(null);
    }

    @Override
    public void recordFailure() {
        circuitBreaker.onError(new RuntimeException("circuit breaker failure"));
    }

    @Override
    public void reset() {
        circuitBreaker.reset();
    }

    @Override
    public boolean isOpen() {
        return circuitBreaker.getState() == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN;
    }

    @Override
    public String getName() {
        return circuitBreaker.getName();
    }
}