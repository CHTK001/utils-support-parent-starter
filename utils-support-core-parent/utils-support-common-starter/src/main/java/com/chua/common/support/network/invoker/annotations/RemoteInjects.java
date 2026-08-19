package com.chua.common.support.network.invoker.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link RemoteInject} 的容器注解，支持在单个方法上重复使用 {@code @RemoteInject}。
 *
 * @since 4.0.0.42
 * @see RemoteInject
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RemoteInjects {
    RemoteInject[] value();
}