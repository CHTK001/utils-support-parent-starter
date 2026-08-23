package com.chua.common.support.network.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 请求方法注解，用于声明式 HTTP 客户端和服务端接口方法。
 *
 * <p>支持两种使用方式：</p>
 * <ul>
 *   <li>简化格式：{@code @RequestMethod("GET /api/users/{id}")} — value 中包含方法和路径</li>
 *   <li>完整格式：{@code @RequestMethod(value = "/api/users/{id}", method = "GET")} — 方法和路径分开声明</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/18
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestMethod {
    String value();
    String method() default "";
}
