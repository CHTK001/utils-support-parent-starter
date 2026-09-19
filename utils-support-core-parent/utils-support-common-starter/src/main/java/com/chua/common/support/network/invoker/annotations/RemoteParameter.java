package com.chua.common.support.network.invoker.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 远程调用参数注解，标注在方法参数上指定参数名称和映射规则。
 *
 * <p>与 {@link RemoteMethod} 配合使用，指定方法参数在远程调用中的名称。
 * 当方法参数上存在协议私有注解（如 {@code @PathVariable}、{@code @RequestParam}、
 * {@code @RequestBody} 等）时，私有注解的优先级更高。</p>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * \@RemoteService(url = "http://api.example.com")
 * public interface UserApi {
 *     \@RemoteMethod("/users/{id}")
 *     User getUser(@RemoteParameter("id") Long id);
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see RemoteMethod
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RemoteParameter {

    /**
     * 参数名称，用于远程调用时的参数映射。
     *
     * @return 参数名称
     */
    String value();

    /**
     * 是否必传。
     *
     * @return 是否必传
     */
    boolean required() default true;

    /**
     * 默认值。
     *
     * @return 默认值
     */
    String defaultValue() default "";
}
