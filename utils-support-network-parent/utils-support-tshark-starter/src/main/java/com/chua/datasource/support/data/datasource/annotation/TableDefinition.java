package com.chua.datasource.support.data.datasource.annotation;

import java.lang.annotation.*;

/**
* 表定义注解。
*
* @author CH
* @since 4.0.0.42
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TableDefinition {

    /**
    * 表名。
    */
    String value();
}
