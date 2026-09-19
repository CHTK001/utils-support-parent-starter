package com.chua.common.support.objects.annotation;

import java.lang.annotation.*;

/**
 * 消息监听注解。
 *
 * <p>标记在方法上，表示该方法用于处理来自消息队列或通道的消息。
 * 可以通过 值 属性指定要监听的消息主题或通道名称。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 *   &#64;OnMessage("order.queue")
 *   public void handleOrder(OrderMessage message) {
 *       // 处理订单消息
 *   }
 * </pre>
 *
 * @author CH
 * @since 2024/12/20
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnMessage {

    /**
     * 消息主题或通道名称。
     *
     * <p>指定要监听的消息来源，为空时根据方法参数自动推断。</p>
     *
     * @return 消息主题，默认为空字符串
     */
    String value() default "";
}
