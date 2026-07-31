package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
 * SPI 支持类型注解，用于标记 SPI 实现所支持的类型或条件。
 *
 * @author CH
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SpiSupport {

    /**
     * 支持的类型名称数组
     *
     * @return 类型名称数组
     */
    String[] value() default {};
}
