package com.chua.common.support.objects.annotation;

import java.lang.annotation.*;

/**
 * 关闭回调注解。
 *
 * <p>标记在方法上，表示该方法在 Bean 所属作用域关闭时被调用。
 * 常用于释放资源（如关闭连接、停止线程等）。
 * 与 {@link jakarta.annotation.PreDestroy} 语义类似。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnClose {
}
