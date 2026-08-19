package com.chua.common.support.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据库列元数据定义注解，用于在自动建表/同步时为字段补充列级元信息。
 *
 * <p>常用于以下场景：</p>
 * <ul>
 *     <li>指定字段的默认值</li>
 *     <li>为字段添加数据库注释</li>
 *     <li>标记字段在表结构同步时是否强制刷新</li>
 * </ul>
 *
 * @since 4.0.0.42
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ColumnDefinition {

    /**
     * 列默认值，空字符串表示不设置默认值。
     *
     * @return 默认值表达式
     */
    String defaultValue() default "";

    /**
     * 列注释，空字符串表示不添加注释。
     *
     * @return 列注释文本
     */
    String comment() default "";

    /**
     * 表结构同步时是否强制刷新该列定义。
     *
     * @return true 表示强制刷新，false 表示不强制刷新
     */
    boolean refresh() default false;
}
