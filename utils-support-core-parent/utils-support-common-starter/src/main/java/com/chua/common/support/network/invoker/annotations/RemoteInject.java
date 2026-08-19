package com.chua.common.support.network.invoker.annotations;

import java.lang.annotation.*;

/**
 * 远程调用结果注入注解，将方法返回值或上下文中的数据按 BeanPath 格式注入到共享上下文中。
 *
 * <p>用于链式调用场景：前一个调用的结果注入到上下文中，后续调用使用。
 * 典型场景：登录获取 token 后注入到请求头，后续 API 调用自动携带。</p>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * \@RemoteService(url = "http://api.example.com")
 * public interface AuthApi {
 *     // 登录后将返回值中的 token 注入到共享上下文的请求头
 *     \@RemoteMethod("/auth/login")
 *     \@RemoteInject(target = "headers.Authorization", source = "result.token", format = "Bearer {0}")
 *     AuthResponse login(@RemoteParameter("username") String user, @RemoteParameter("password") String pass);
 * }
 *
 * // 后续调用自动携带 Authorization 头
 * \@RemoteService(url = "http://api.example.com")
 * public interface UserApi {
 *     \@RemoteMethod("/users/{id}")
 *     User getUser(@PathVariable("id") Long id);
 * }
 * }</pre>
 *
 * @since 4.0.0.42
 * @see RemoteMethod
 * @see RemoteService
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RemoteInjects.class)
public @interface RemoteInject {

    /**
     * 注入目标路径（BeanPath 格式）。
     *
     * <p>指定注入到共享上下文的哪个位置：</p>
     * <ul>
     *   <li>{@code "headers.X"} — 注入到默认请求头 {@code X}</li>
     *   <li>{@code "attributes.X"} — 注入到共享属性 {@code X}</li>
     * </ul>
     *
     * @return 目标路径
     */
    String target();

    /**
     * 数据来源路径（BeanPath 格式）。
     *
     * <p>指定从何处获取数据：</p>
     * <ul>
     *   <li>{@code "result.xxx"} — 从方法返回值中提取</li>
     *   <li>{@code "args[0].xxx"} — 从方法参数中提取</li>
     *   <li>{@code "attributes.xxx"} — 从共享上下文中提取</li>
     * </ul>
     *
     * @return 来源路径
     */
    String source();

    /**
     * 格式化模板，{@code {0}} 表示来源值的占位符。
     *
     * <p>例如 {@code "Bearer {0}"} 将来源值包装为 Bearer token 格式。</p>
     *
     * @return 格式化模板，为空时直接使用来源值
     */
    String format() default "";
}