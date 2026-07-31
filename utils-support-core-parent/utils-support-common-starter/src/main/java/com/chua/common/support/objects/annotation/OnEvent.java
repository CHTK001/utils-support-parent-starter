package com.chua.common.support.objects.annotation;

import java.lang.annotation.*;

/**
 * 事件监听注解。
 *
 * <p>标记在方法上，表示该方法用于监听指定类型的事件。
 * 当容器发布匹配类型的事件时，被标记的方法会自动调用。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 *   &#64;OnEvent(UserLoginEvent.class)
 *   public void handleUserLogin(UserLoginEvent event) {
 *       // 处理用户登录事件
 *   }
 * </pre>
 *
 * @author CH
 * @since 2024/12/20
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnEvent {

    /**
     * 要监听的事件类型。
     *
     * <p>只有发布的事件是该类型或其子类型时，回调方法才会被触发。</p>
     *
     * @return 事件类型
     */
    Class<?> value();
}
