package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
 * 条件注解：当类路径下存在指定的类时，相关的SPI配置或组件才会生效。
 * <p>
 * 用于SPI机制中的条件装配，判断当前运行环境中是否包含特定的依赖类。
 * </p>
 *
 * @author CH
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConditionalOnClass {

    /**
     * 指定需要检查的类的全限定名（字符串形式）。
     * <p>
     * 当类路径下存在这些类时，条件成立。使用字符串形式可以避免在编译时强依赖该类。
     * </p>
     *
     * @return 类的全限定名数组
     */
    String[] value() default {};

    /**
     * 指定需要检查的具体类对象。
     * <p>
     * 当类路径下存在这些类时，条件成立。
     * </p>
     *
     * @return 类对象数组
     */
    Class<?>[] classes() default {};
}