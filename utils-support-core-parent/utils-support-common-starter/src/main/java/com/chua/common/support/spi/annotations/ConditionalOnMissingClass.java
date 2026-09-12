package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
* 条件注解：当指定的类在类路径中缺失时条件成立
* <p>通常用于 SPI 或组件的条件装配，确保在特定依赖缺失时才加载当前组件</p>
*
* @author CH
* @since 4.0.0.42
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConditionalOnMissingClass {

    /**
    * 需要检查的类的全限定名数组
    * <p>当这些类在类路径中均不存在时，条件成立</p>
    *
    * @return 类全限定名数组
     */
    String[] value() default {};

    /**
    * 需要检查的类对象数组
    * <p>当这些类在类路径中均不存在时，条件成立</p>
    *
    * @return 类对象数组
     */
    Class<?>[] classes() default {};
}
