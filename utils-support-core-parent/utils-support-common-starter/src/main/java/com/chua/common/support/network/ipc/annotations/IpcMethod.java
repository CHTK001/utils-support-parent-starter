package com.chua.common.support.network.ipc.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * IPC 方法注解，用于标记可被浏览器端调用的 Java 方法。
 * <p>
 * 该注解支持在类和接口级别使用，也可应用于方法定义。
 * 通过指定 value 属性来标识唯一的 IPC 方法名称。
 * </p>
 *
 * @since 2026/07/18
 * @see com.chua.common.support.network.ipc.IpcServer
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface IpcMethod {
    /**
     * 该方法或类的唯一标识符。
     * <p>
     * 此值将作为浏览器端调用时的方法名称。
     * </p>
     *
     * @return 方法名称字符串
     */
    String value();
}
