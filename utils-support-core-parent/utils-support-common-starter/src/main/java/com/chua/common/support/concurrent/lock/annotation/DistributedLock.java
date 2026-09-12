package com.chua.common.support.concurrent.lock.annotation;

import java.lang.annotation.*;

/**
* 分布式锁注解。
*
* @author CH
* @since 4.0.0.42
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    /**
    * 锁的名称，支持 SpEL 表达式。
     */
    String name();

    /**
    * 是否使用公平锁。
     */
    boolean fair() default false;

    /**
    * 等待锁的时间（毫秒）。
     */
    long waitTime() default 5000;

    /**
    * 持有锁的超时时间（毫秒），-1表示不设置。
     */
    long leaseTime() default -1;

    /**
    * 锁的类型，用于区分不同的锁实现策略。
     */
    String lockType() default "";

    /**
    * 获取锁失败时的重试间隔（毫秒）。
     */
    long retryInterval() default 100;

    /**
    * 最大重试次数。
     */
    int maxRetries() default 3;

    /**
    * 是否忽略异常，默认为false。
     */
    boolean ignoreException() default false;

    /**
    * 自定义异常处理器类名（可选）。
     */
    Class<?> exceptionHandler() default Void.class;
}
