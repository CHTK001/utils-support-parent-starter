package com.chua.common.support.concurrent.circuitbreaker.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 熔断降级注解，用于方法级熔断控制。
 *
 * <p>所有属性支持 {@code ${...}} 占位符和 {@code #{...}} SpEL 表达式。</p>
 *
 * @since 4.0.0.42
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CircuitBreaker {

    /**
     * 熔断器名称（唯一标识）。
     *
     * <p>不填默认使用 {@code 类名.方法名} 作为熔断器名称。</p>
     *
     * @return 熔断器名称
     */
    String name() default "";

    /**
     * 失败阈值，达到此次数后熔断打开。
     *
     * <p>支持 {@code ${...}} 和 {@code #{...}} 表达式。</p>
     *
     * @return 失败次数，默认 5
     */
    String failureThreshold() default "5";

    /**
     * 成功阈值，达到此次数后熔断关闭。
     *
     * <p>支持 {@code ${...}} 和 {@code #{...}} 表达式。</p>
     *
     * @return 成功次数，默认 2
     */
    String successThreshold() default "2";

    /**
     * 熔断打开后的等待时间（毫秒），之后进入半开状态。
     *
     * <p>支持 {@code ${...}} 和 {@code #{...}} 表达式。</p>
     *
     * @return 等待时间，默认 60000
     */
    String waitDuration() default "60000";

    /**
     * 恢复时间（毫秒），与 {@link #waitDuration()} 相同，语义别名。
     *
     * @return 恢复时间，默认 ""
     */
    String recoveryTime() default "";

    /**
     * 熔断拒绝时调用的回退方法名。
     *
     * @return 回退方法名，为空时抛出熔断异常
     */
    String fallback() default "";
}