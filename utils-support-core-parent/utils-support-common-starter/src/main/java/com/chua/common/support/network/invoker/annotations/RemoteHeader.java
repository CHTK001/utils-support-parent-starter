package com.chua.common.support.network.invoker.annotations;

import java.lang.annotation.*;

/**
* 远程调用请求头注解，指定方法或参数级别的 HTTP 请求头。
*
* <p>标注在方法上：静态指定请求头的名称和值。</p>
* <p>标注在参数上：参数值作为请求头的值，参数名或 {@link #name()} 作为请求头名称。</p>
*
* <p><b>使用示例：</b></p>
* <pre>{@code
* \@RemoteService(url = "http://api.example.com")
* public interface UserApi {
*     // 方法级别：静态请求头
*     \@RemoteMethod("/users/{id}")
*     \@RemoteHeader(name = "Authorization", value = "Bearer token123")
*     User getUser(@PathVariable("id") Long id);
*
*     // 参数级别：动态请求头，参数值作为 header 值
*     \@RemoteMethod("/users")
*     User createUser(@RemoteHeader("X-Request-Id") String requestId, @RequestBody User user);
* }
* }</pre>
*
* @author CH
* @since 4.0.0.42
* @see RemoteMethod
* @see RemoteService
 */
@Target({ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RemoteHeaders.class)
public @interface RemoteHeader {

    /**
    * 请求头名称。
    *
    * <p>标注在参数上时，如果未指定则使用参数名。</p>
    *
    * @return 请求头名称
     */
    String name();

    /**
    * 请求头值（仅方法级别有效）。
    *
    * <p>标注在参数上时忽略此属性，值由参数值提供。</p>
    *
    * @return 请求头值
     */
    String value() default "";
}