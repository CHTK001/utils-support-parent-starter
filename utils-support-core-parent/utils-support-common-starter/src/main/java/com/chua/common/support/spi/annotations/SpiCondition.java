package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
 * SPI 条件注解
 * <p>
 * 用于 SPI (服务 提供者 接口) 的条件装配，
 *     可以根据类路径中是否存在指定的类，或者自定义条件是否满足，
 *     来决定是否加载该 SPI 实现。
 * </p>
 *
 * <p>
 *     主要特性：
 * <ul>
 *   <li>支持基于类路径中是否存在指定类进行条件判断</li>
 *   <li>支持基于自定义 {@link SpiCondition.Condition} 实现进行条件判断</li>
 *   <li>可作用于类或字段级别</li>
 *   <li>与 SPI 机制结合，实现灵活的扩展点加载</li>
 * </ul>
 * </p>
 *
 * <p>
 *     使用示例：
 * <pre>{@code
 * // 基于类路径中是否存在指定类进行条件装配
 * @SpiCondition("com.example.RequiredClass")
 * @Spi("conditional-service")
 * public class ConditionalService implements Service {
 *     // 当 RequiredClass 存在于 classpath 中时，该实现才会被加载
 * }
 *
 * // 基于自定义条件进行条件装配
 * @SpiCondition(onCondition = CustomCondition.class)
 * @Spi("custom-service")
 * public class CustomService implements Service {
 *     // 当 CustomCondition.isCondition() 返回 true 时，该实现才会被加载
 * }
 * }</pre>{
 * // 当 习俗条件.是否条件() 返回 true 时，该实现才会被加载
 * }
 * }</pre>
 * </p>
 *
 * @since 2024-01-01
 * @版本 1.0.0
 * @see SpiCondition.Condition
 * @author CH
*/
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface SpiCondition {
    /**
    * 指定的类名
    * <p>
    * 当指定的类存在于 类路径 中时，该 SPI 实现才会被加载。
    *     可以指定多个类，通常要求全部存在才会生效。
    * </p>
    *
    * @return 类名数组
    */
    String[] value() default {};

    /**
    * 自定义条件类
    * <p>
    *     指定实现了 {@link SpiCondition.Condition} 接口的类。
    *     当 {@link SpiCondition.Condition#isCondition()} 返回 true 时，
    *     该 SPI 实现才会被加载。
    * </p>
    *
    * @return 条件类数组
    */
    Class<? extends SpiCondition.Condition>[] onCondition() default {};

    /**
    * SPI 条件接口
    * <p>
    *     用于自定义 SPI 加载的条件逻辑。
    *     实现该接口并配合 {@link SpiCondition#onCondition()} 使用。
    * </p>
    *
    * @author CH
    * @since 2024-01-01
    */
    interface Condition {
        /**
        * 判断条件是否满足
        * <p>
        *     返回 true 表示条件满足，加载该 SPI 实现；
        *     返回 false 表示条件不满足，不加载该 SPI 实现。
        * </p>
        *
        * @return 条件是否满足，true 表示满足，false 表示不满足
        */
        boolean isCondition();
    }
}

