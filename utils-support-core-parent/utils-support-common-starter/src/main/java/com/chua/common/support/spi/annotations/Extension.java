package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
 * 扩展点注解，用于为 SPI 实现指定扩展名。
 *
 * @since 4.0.0.42
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Extension {

    /**
     * 扩展名
     *
     * @return 扩展名字符串
     */
    String value();
}
