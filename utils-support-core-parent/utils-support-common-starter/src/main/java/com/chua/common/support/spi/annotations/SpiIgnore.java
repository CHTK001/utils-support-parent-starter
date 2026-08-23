package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
 * SPI 忽略注解
 * <p>
 *     SPI (Service Provider Interface) 忽略注解，用于标记指定的类不参与 SPI 的自动扫描与加载。
 *     通常用于在某些场景下排除特定的 SPI 实现类。
 * </p>
 *
 * <p>
 *     主要特性：
 * <ul>
 *   <li>标记实现类不参与 SPI 机制的自动加载</li>
 *   <li>支持在类级别进行忽略配置</li>
 *   <li>可与其他 SPI 相关注解配合使用</li>
 *   <li>增强 SPI 扩展机制的灵活性</li>
 * </ul>
 * </p>
 *
 * <p>
 *     使用示例：
 * <pre>{@code
 * @SpiIgnore
 * @Spi("old-service")
 * public class OldService implements Service {
 *     // 该类将被 SPI 机制忽略，不会被自动加载
 * }
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 2024-01-01
 * @version 1.0.0
 * @see Spi
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface SpiIgnore {
}

