package com.chua.common.support.concurrent.timeout.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 超时控制注解，用于方法级超时控制。
 *
 * <p>所有属性支持 {@code ${...}} 占位符和 {@code #{...}} SpEL 表达式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Timeout {

    /**
     * 超时名称（唯一标识）。
     *
     * @return 名称
     */
    String name() default "";

    /**
     * 超时时间（毫秒）。
     *
     * <p>支持 {@code ${...}} 和 {@code #{...}} 表达式。</p>
     *
     * @return 超时毫秒数
     */
    String timeout() default "3000";

    /**
     * 超时发生时的回退方法名。
     *
     * <p>要求与该方法位于同一类中，且参数签名完全一致。
     * 为空时超时抛出异常。</p>
     *
     * @return 回退方法名
     */
    String fallback() default "";
}