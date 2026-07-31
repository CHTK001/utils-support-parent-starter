package com.chua.common.support.concurrent.lock.annotation;

import java.lang.annotation.*;

/**
 * 限流注解。
 * <p>
 * 用于在方法上添加限流控制，支持自定义令牌桶参数、公平锁策略以及拒绝时的回退机制。
 *
 * @author CH
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
     * 是否使用公平锁模式。
     *
     * @return true 表示公平锁，false 表示非公平锁
     */
    boolean fair() default false;

    /**
     * 初始许可数（令牌数）。
     *
     * @return 许可数
     */
    int permits() default 1;

    /**
     * 获取许可时等待的最长时间（毫秒），0 表示不等待。
     *
     * @return 等待时间
     */
    long waitTime() default 0;

    /**
     * 锁类型标识，用于区分不同的限流实现策略（如分布式锁、本地锁等）。
     *
     * @return 锁类型
     */
    String lockType() default "";

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
