package com.chua.common.support.annotation;

import java.lang.annotation.*;

/**
 * 标记注解，用于指示被标注的类型或方法的返回值类型在统一返回体处理中保持原样。
 *
 * <p>常用于以下场景：</p>
 * <ul>
 *     <li>标记 Controller 方法，使其返回值不被统一返回体包装</li>
 *     <li>标记 Controller 类，使该类所有方法返回值保持原样</li>
 * </ul>
 *
 * @since 4.0.0.42
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IgnoreReturnType {
}
