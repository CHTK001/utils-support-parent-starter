package com.chua.common.support.concurrent.circuitbreaker.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 熔断降级注解，用于方法级熔断控制。
 *
 * <p>支持内存状态机、Sentinel 等熔断策略，通过 {@link com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerFlow}
 * 统一管理熔断器实例。熔断打开时支持 fallback 回退方法。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CircuitBreaker {

    /**
     * 熔断器名称（唯一标识）。
     *
     * @return 熔断器名称
     */
    String name();

    /**
     * 失败阈值，达到此次数后熔断打开。
     *
     * @return 失败次数
     */
    int failureThreshold() default 5;

    /**
     * 成功阈值，达到此次数后熔断关闭。
     *
     * @return 成功次数
     */
    int successThreshold() default 2;

    /**
     * 熔断打开后的等待时间（毫秒），之后进入半开状态。
     *
     * @return 等待时间
     */
    long waitDuration() default 60000;

    /**
     * 熔断拒绝时调用的回退方法名。
     *
     * <p>要求必须与目标方法位于同一类中，且参数签名完全一致。
     * 若为空字符串，则抛出熔断异常。</p>
     *
     * @return 回退方法名
     */
    String fallback() default "";
}