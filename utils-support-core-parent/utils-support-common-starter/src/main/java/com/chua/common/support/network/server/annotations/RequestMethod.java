package com.chua.common.support.network.server.annotations;

import com.chua.common.support.network.http.HttpMethod;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 通用请求方法注解，用于将请求映射到处理方法上。
 *
 * <p>可标注在类或方法级别：类级别为公共前缀，方法级别为具体路径。
 * 适用于 HTTP 服务端、IPC 服务端路由注册等场景。</p>
 *
 * <p>示例：</p>
 * <pre>{@code
 * @RequestMethod("/api")
 * public class UserApi {
 *     @RequestMethod(value = "/users/{id}", method = HttpMethod.GET)
 *     public User getUser(@PathVariable("id") String id) { ... }
 * }
 * }</pre>
 *
 * @author CH
 * @since 2024/12/12
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestMethod {

    /**
     * 请求路径（支持多路径）
     *
     * @return 路径数组
     */
    String[] value() default {};

    /**
     * HTTP 方法（支持多方法，空数组表示匹配所有方法）
     *
     * @return HTTP 方法数组
     */
    HttpMethod[] method() default {};

    /**
     * 描述信息
     *
     * @return 描述
     */
    String description() default "";
}
