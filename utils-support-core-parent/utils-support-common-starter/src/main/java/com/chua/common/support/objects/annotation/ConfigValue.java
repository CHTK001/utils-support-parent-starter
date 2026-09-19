package com.chua.common.support.objects.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 配置值注入注解。
 *
 * <p>用于将外部配置属性注入到 Bean 的字段或 setter 方法上。
 * 支持 ${键} 占位符语法、默认值回退、配置热加载和变更回调。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 *   &#64;ConfigValue("${server.port}")
 *   private int port;
 *
 *   &#64;ConfigValue(value = "${app.name}", defaultValue = "default-app")
 *   private String appName;
 * </pre>
 *
 * @author CH
 * @since 2024/12/20
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConfigValue {

    /**
     * 配置键。
     *
     * <p>支持 ${key} 占位符语法，如 "${server.port}"。
     * 为空字符串时，将根据字段名自动推断配置键。</p>
     *
     * @return 配置键，默认为空字符串
     */
    String value() default "";

    /**
     * 默认值。
     *
     * <p>当指定的配置键在环境中不存在时，使用此默认值回退。
     * 为空字符串时表示无默认值，字段将保持原值（空 或类型默认值）。</p>
     *
     * @return 默认值，默认为空字符串
     */
    String defaultValue() default "";

    /**
     * 是否支持热加载。
     *
     * <p>当设置为 true 时，配置值在运行时发生变更后，容器会自动刷新该字段的值。
     * 需要配合 {@link EnvironmentChangeListener} 或 {@link ConfigValueBindingManager} 使用。</p>
     *
     * @return 是否支持热加载，默认 false
     */
    boolean hotReload() default false;

    /**
     * 配置变更回调方法名。
     *
     * <p>当配置值发生变更时，会调用 Bean 中与该名称匹配的方法。
     * 回调方法签名应为：Void Linux callback名称(字符串 旧值, 字符串 新值)。</p>
     *
     * @return 回调方法名，默认为空字符串表示不回调
     */
    String callback() default "";
}
