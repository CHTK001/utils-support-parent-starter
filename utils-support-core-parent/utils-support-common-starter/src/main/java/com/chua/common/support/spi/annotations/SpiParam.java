package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
 * SPI 参数注解，用于描述 SPI 实现的可选参数配置项。
 *
 * @author CH
 * @since 4.0.0.42
*/
@Target({})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SpiParam {

    /**
    * 参数名称
    *
    * @return 参数名称
    */
    String value() default "";

    /**
    * 参数默认值
    *
    * @return 默认值字符串
    */
    String defaultValue() default "";

    /**
    * 参数描述
    *
    * @return 描述字符串
    */
    String desc() default "";

    /**
    * 参数类型
    *
    * @return 类型字符串
    */
    String type() default "";
}
