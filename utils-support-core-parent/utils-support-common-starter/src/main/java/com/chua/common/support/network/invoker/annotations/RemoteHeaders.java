package com.chua.common.support.network.invoker.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
* {@link RemoteHeader} 的容器注解，支持在单个方法上重复使用 {@code @RemoteHeader}。
*
* @author CH
* @since 4.0.0.42
* @see RemoteHeader
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RemoteHeaders {
    /**
     * 值。
     *
     * @return Remote请求头 对象
     */
    RemoteHeader[] value();
}