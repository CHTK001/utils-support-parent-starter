package com.chua.common.support.objects.annotation;

import java.lang.annotation.*;

/**
 * 错误回调注解。
 *
 * <p>标记在方法上，表示该方法在 Bean 所属作用域发生错误时被调用。
 * 常用于错误处理、异常日志记录等操作。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 *   &#64;OnError
 *   public void onError(Throwable e) {
// [P3C E1 豁免] 场景化输出，非业务日志
 *       System.err.println("发生错误: " + e.getMessage());
 *   }
 * </pre>
 *
 * @author CH
 * @since 2026/07/20
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnError {
}