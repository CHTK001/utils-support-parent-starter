package com.chua.ast.support.annotation;

import java.lang.annotation.*;

/**
* 重试注解，编译期自动插入重试逻辑
*
* <p>标记在方法上，编译期会将方法体包装在重试逻辑中，
* 支持固定间隔和指数退避两种策略。</p>
*
* <p>使用示例：</p>
* <pre>{@code
* // 固定间隔重试：最多重试3次，每次间隔100ms
* @Retry(times = 3, delay = 100)
* public void process() { ... }
*
* // 指数退避重试：最多重试3次，初始间隔100ms，最大间隔5000ms
* @Retry(times = 3, delay = 100, maxDelay = 5000, strategy = RetryStrategy.EXPONENTIAL)
* public void process() { ... }
* }</pre> }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Retry {

    /**
    * 最大重试次数
    *
    * @return 重试次数
     */
    int times() default 3;

    /**
    * 重试间隔（毫秒）
    *
    * @return 间隔毫秒数
     */
    long delay() default 100;

    /**
    * 最大延迟（毫秒），仅指数退避策略生效
    *
    * @return 最大延迟毫秒数
     */
    long maxDelay() default 5000;

    /**
    * 重试策略
    *
    * @return 重试策略枚举
     */
    RetryStrategy strategy() default RetryStrategy.FIXED;

    /**
    * 重试策略枚举
    *
    * @author CH
    * @since 4.0.0.42
     */
    enum RetryStrategy {
        /** 固定间隔 */
        FIXED,
        /** 指数退避 */
        EXPONENTIAL
    }
}
