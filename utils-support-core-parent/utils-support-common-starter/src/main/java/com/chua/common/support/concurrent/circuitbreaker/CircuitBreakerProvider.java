package com.chua.common.support.concurrent.circuitbreaker;

/**
 * 熔断器提供者 SPI 接口，定义熔断降级的核心行为。
 *
 * <p>通过 SPI 支持不同的熔断实现（如内存状态机、Sentinel、Resilience4j 等）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface CircuitBreakerProvider {

    /**
     * 尝试获取执行许可。
     *
     * <p>熔断器处于关闭状态时返回 true，允许执行。
     * 熔断器处于打开状态时返回 false，拒绝执行触发降级。</p>
     *
     * @return 获取成功返回 true，拒绝执行返回 false
     */
    boolean tryAcquire();

    /**
     * 记录执行成功，熔断器根据成功次数决定是否关闭熔断。
     */
    void recordSuccess();

    /**
     * 记录执行失败，熔断器根据失败次数和阈值决定是否打开熔断。
     */
    void recordFailure();

    /**
     * 重置熔断器到初始关闭状态。
     */
    void reset();

    /**
     * 判断熔断器当前是否处于打开状态。
     *
     * @return 打开返回 true
     */
    boolean isOpen();

    /**
     * 获取熔断器名称。
     *
     * @return 名称
     */
    String getName();
}
