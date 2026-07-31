package com.chua.common.support.objects.lifecycle;

import java.lang.annotation.*;

/**
 * PreDestroy 注解，标记销毁方法。
 *
 * @author CH
 * @since 2024/12/20
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AutoPreDestroy {
}