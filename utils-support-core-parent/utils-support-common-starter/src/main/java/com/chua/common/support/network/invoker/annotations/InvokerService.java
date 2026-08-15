package com.chua.common.support.network.invoker.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 服务调用标记注解，标注在接口上表示该接口可通过 {@code Invoker} 创建代理。
 *
 * <p>当接口未标注 {@code @RequestMethod} 或 Spring MVC 类级注解时，
 * 可通过此注解指定基础 URL 或服务名称，由 {@code Invoker} 实现解析。</p>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * \@InvokerService("http://api.example.com")
 * public interface UserApi {
 *     \@GetMapping("/api/users/{id}")
 *     User getUser(@PathVariable("id") Long id);
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface InvokerService {

    /**
     * 服务的基础 URL 或服务名称。
     *
     * @return 基础 URL 或服务名称
     */
    String value() default "";
}