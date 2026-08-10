package com.chua.common.support.concurrent.rate.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流注解，用于方法级限流控制。
 *
 * <p>支持 Guava 令牌桶、信号量等限流策略，通过 {@link com.chua.common.support.concurrent.rate.RateLimiterFlow}
 * 统一管理限流器实例。拒绝时支持 fallback 回退方法。</p>
 *
 * @author CH
 * @since 2026/07/24
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiter {

    /**
     * 限流器名称（唯一标识）。
     *
     * @return 限流器名称
     */
    String name();

    /**
     * 每秒许可数（令牌数）。
     *
     * @return 许可数
     */
    double permitsPerSecond() default 1;

    /**
     * 获取许可时等待的最长时间（毫秒），0 表示不等待。
     *
     * @return 等待时间
     */
    long waitTime() default 0;

    /**
     * 预热时间（秒），0 表示不预热。
     *
     * @return 预热时间
     */
    long warmupPeriod() default 0;

    /**
     * 限流拒绝时调用的回退方法名。
     * <p>
     * 要求：必须与目标方法位于同一类中，且参数签名完全一致。
     * 若为空字符串，则抛出限流异常。
     *
     * @return 回退方法名
     */
    String fallback() default "";
}