package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
 * 条件注解：基于配置属性决定是否加载当前类
 * <p>
 * 用于 SPI 机制中，根据环境配置属性的值来条件化地注册或实例化组件。
 *
 * @author CH
 * @since 4.0.0.42
*/
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConditionalOnProperty {

    /**
    * 配置属性的名称（键名）
    *
    * @return 属性名称
    */
    String name();

    /**
    * 配置属性的名称（键名），通常作为 {@link #name()} 的别名
    * <p>
    * 当仅需要指定属性名时，可直接使用此属性，例如：{@code @ConditionalOnProperty("my.property")}
    *
    * @return 属性名称
    */
    String value() default "";

    /**
    * 是否进行属性值匹配
    * <p>
    * 如果为 {@code true}，则需要配置属性的值与期望值匹配；
    * 如果为 {@code false}，则仅检查属性是否存在（或忽略具体值的匹配）。
    *
    * @return 是否匹配属性值，默认为 {@code true}
    */
    boolean matchValue() default true;

    /**
    * 期望匹配的属性值，或当属性未配置时使用的默认值
    * <p>
    * 结合 {@link #matchValue()} 使用，用于判断配置项的具体值是否符合预期。
    *
    * @return 期望的属性值或默认值
    */
    String defaultValue() default "";

    /**
    * 在匹配属性值时是否忽略大小写
    *
    * @return 是否忽略大小写，默认为 {@code false}
    */
    boolean ignoreCase() default false;
}
