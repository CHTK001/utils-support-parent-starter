package com.chua.datasource.support.data.datasource.annotation;

import java.lang.annotation.*;

/**
 * 主键标识注解。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Id {
}