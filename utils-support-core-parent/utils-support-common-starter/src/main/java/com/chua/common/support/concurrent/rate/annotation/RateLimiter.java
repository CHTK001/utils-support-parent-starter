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
 * <p>所有数值属性支持 {@code ${...}} 占位符和 {@code #{...}} SpEL 表达式。</p>
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
     * <p>支持 {@code ${...}} 和 {@code #{...}} 表达式。</p>
     *
     * @return 许可数
     */
    String permitsPerSecond() default "1";

    /**
     * 获取许可时等待的最长时间（毫秒），0 表示不等待。
     *
     * <p>支持 {@code ${...}} 和 {@code #{...}} 表达式。</p>
     *
     * @return 等待时间
     */
    String waitTime() default "0";

    /**
     * 预热时间（秒），0 表示不预热。
     *
     * <p>支持 {@code ${...}} 和 {@code #{...}} 表达式。</p>
     *
     * @return 预热时间
     */
    String warmupPeriod() default "0";

    /**
     * 限流拒绝时调用的回退方法名。
     *
     * <p>要求：必须与目标方法位于同一类中，且参数签名完全一致。
     * 若为空字符串，则抛出限流异常。</p>
     *
     * @return 回退方法名
     */
    String fallback() default "";
}