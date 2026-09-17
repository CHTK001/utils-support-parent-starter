package com.chua.common.support.concurrent.dispatcher;

import java.lang.annotation.*;

/**
* 订阅注解，用于标记消息的订阅方法。
* <p>
* 标注了该注解的方法必须有且仅有一个入参（消息体），
* 当有消息发布到对应主题时，该方法将被反射调用。
* </p>
*
* @author CH
* @since 2025-11-26
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Subscribe {

    /**
    * 订阅的主题名称。
    * <p>
    * 支持多个主题，方法将订阅此处指定的所有主题。
    * </p>
    *
    * @return 主题名称数组
    */
    String[] topic();

    /**
    * 指定注册到哪个 {@link DispatcherProvider} 的 SPI 类型标识。
    * <p>
    * 为空时默认注册给第一个 Provider；指定时只注册给匹配该 SPI 类型的 Provider。
    * </p>
    *
    * @return SPI 类型标识
    */
    String type() default "";
}
