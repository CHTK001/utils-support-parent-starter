package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
 * SPI 描述注解，用于描述 SPI 实现的功能、类型和详细说明。
 *
 * @since 4.0.0.42
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SpiDescribe {

    /**
     * SPI 实现的功能描述
     *
     * @return 描述字符串
     */
    String value() default "";

    /**
     * SPI 实现的类型分类
     *
     * @return 类型字符串
     */
    String type() default "";

    /**
     * SPI 实现的详细说明
     *
     * @return 详细说明字符串
     */
    String desc() default "";

    /**
     * SPI 实现的参数配置列表
     *
     * @return 参数数组
     */
    SpiParam[] optional() default {};
}
