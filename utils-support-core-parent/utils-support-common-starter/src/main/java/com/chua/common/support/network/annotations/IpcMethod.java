package com.chua.common.support.network.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
* IPC 方法注解，用于声明式 IPC 服务接口方法。
*
* <p>与 {@link RequestMethod} 结构一致，仅限 IPC 协议使用。</p>
*
* @author CH
* @since 2026/07/18
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface IpcMethod {
    /**
     * 值。
     *
     * @return 结果字符串
     */
    String value();
    String method() default "";
}
