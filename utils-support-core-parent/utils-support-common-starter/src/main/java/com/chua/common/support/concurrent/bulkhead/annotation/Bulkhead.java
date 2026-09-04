package com.chua.common.support.concurrent.bulkhead.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 并发隔离注解，用于方法级并发数控制。
 *
 * <p>所有属性支持 {@code ${...}} 占位符和 {@code #{...}} SpEL 表达式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Bulkhead {

    /**
     * 隔离名称（唯一标识）。
     *
     * @return 名称
     */
    String name() default "";

    /**
     * 最大并发数。
     *
     * <p>支持 {@code ${...}} 和 {@code #{...}} 表达式。</p>
     *
     * @return 最大并发数
     */
    String maxConcurrent() default "10";

    /**
     * 是否公平模式。
     *
     * @return 是否公平
     */
    boolean fair() default true;

    /**
     * 到达并发上限时的回退方法名。
     *
     * <p>支持两种引用形式：</p>
     * <ul>
     *   <li>{@code methodName}：目标方法同类的同名方法（参数签名一致）；</li>
     *   <li>{@code beanName#methodName}：Spring 容器中指定 Bean 的方法
     *       （可将降级方法收敛到公共降级 Bean，如统一空降级）。</li>
     * </ul>
     * <p>为空时抛出并发异常。</p>
     *
     * @return 回退方法名
     */
    String fallback() default "";
}