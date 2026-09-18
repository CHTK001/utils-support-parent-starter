package com.chua.datasource.support.data.datasource.annotation;

import java.lang.annotation.*;

/**
* 列定义注解。
*
* @author CH
* @since 4.0.0.42
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ColumnDefinition {

    /**
    * 列名（数据库字段名）。
    * @return 结果字符串
    */
    String value();

    /**
    * 列注释（可选）。
    */
    String comment() default "";

    /**
    * 默认值（可选）。
    */
    String defaultValue() default "";
}
