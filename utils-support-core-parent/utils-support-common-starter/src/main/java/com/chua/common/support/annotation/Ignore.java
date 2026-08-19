package com.chua.common.support.annotation;

import java.lang.annotation.*;

/**
 * 标记注解，用于指示被标注的类型或方法在统一返回体处理中被忽略。
 *
 * <p>常用于以下场景：</p>
 * <ul>
 *     <li>标记 Controller 类或方法，使其返回值不被统一返回体包装</li>
 *     <li>标记接口或方法，使其在鉴权/拦截链路中被放行</li>
 * </ul>
 *
 * @since 4.0.0.42
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Ignore {
}
