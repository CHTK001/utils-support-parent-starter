package com.chua.datasource.support.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
* 实体类表名注解。
* <p>
* 引擎生成 SQL 时优先读取该注解指定的表名；未标注时回退到
* 实体类简单名的默认转换规则（驼峰转下划线）。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TableName {

    /**
    * 表名。
    *
    * @return 表名
     */
    String value();
}
