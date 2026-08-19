package com.chua.common.support.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记注解，用于指示数据库列在自动建表/同步时被忽略。
 *
 * <p>常用于以下场景：</p>
 * <ul>
 *     <li>实体类字段标记此注解后，自动建表时不会生成对应数据库列</li>
 *     <li>表结构同步时，该字段不会被纳入同步范围</li>
 * </ul>
 *
 * @since 4.0.0.42
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ColumnIgnore {
}
