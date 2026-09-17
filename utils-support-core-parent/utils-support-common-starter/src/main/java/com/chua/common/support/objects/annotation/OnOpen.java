package com.chua.common.support.objects.annotation;

import java.lang.annotation.*;

/**
 * 打开回调注解。
 *
 * <p>标记在方法上，表示该方法在 Bean 所属作用域打开或激活时被调用。
 * 常用于初始化资源、建立连接等启动操作。
 * 与 {@link jakarta.annotation.PostConstruct} 语义类似。</p>
 *
 * @author CH
 * @since 2024/12/20
*/
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnOpen {
}
