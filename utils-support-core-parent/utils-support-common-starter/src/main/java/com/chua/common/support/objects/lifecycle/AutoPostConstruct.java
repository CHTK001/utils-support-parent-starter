package com.chua.common.support.objects.lifecycle;

import java.lang.annotation.*;

/**
 * PostConstruct 注解，标记初始化方法。
 *
 * @author CH
 * @since 2024/12/20
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AutoPostConstruct {
}