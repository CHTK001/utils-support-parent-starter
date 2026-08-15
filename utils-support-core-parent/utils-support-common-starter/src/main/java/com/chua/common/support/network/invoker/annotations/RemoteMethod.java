package com.chua.common.support.network.invoker.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 远程调用方法注解，标注在方法上指定远程路径和 HTTP 方法。
 *
 * <p>与类级别的 {@link RemoteService} 配合使用，路径会与类级 {@link RemoteService#path()} 组合成完整地址。
 * 当方法上存在协议私有注解（如 {@code @GetMapping}、{@code @PostMapping}、{@code @IpcMethod} 等）时，
 * 私有注解的优先级更高，优先使用其中的路径信息。</p>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * \@RemoteService(url = "http://api.example.com", path = "/api")
 * public interface UserApi {
 *     // 完整路径 = /api/users/{id}，GET 方法
 *     \@RemoteMethod(value = "/users/{id}", method = "GET")
 *     User getUser(@RemoteParameter("id") Long id);
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see RemoteService
 * @see RemoteParameter
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RemoteMethod {

    /**
     * 方法路径，与类级别 {@link RemoteService#path()} 组合成完整地址。
     *
     * @return 路径
     */
    String value();

    /**
     * HTTP 方法（如 GET、POST、PUT、DELETE）。
     *
     * <p>仅对 HTTP 协议有效。为空时由 Invoker 实现自行推断（如根据方法名或默认值）。</p>
     *
     * @return HTTP 方法
     */
    String method() default "";
}