package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
 * SPI 排序注解
 * <p>
 * 用于指定 SPI 实现的加载优先级顺序，数值越大优先级越高。
 * 通常与 {@link Spi} 注解配合使用，用于控制多个 SPI 实现的加载顺序。
 * <p>
 * 优先级规则：
 * <ul>
 *   <li>数值越大，优先级越高，越先被加载</li>
 *   <li>高优先级：100、50 等正数</li>
 *   <li>默认优先级：0</li>
 *   <li>低优先级：-50、-100 等负数</li>
 * </ul>
 * <p>
 * 使用场景：
 * <ul>
 *   <li>当多个实现类实现同一接口时，通过此注解控制加载顺序</li>
 *   <li>用于缓存、日志、序列化等需要优先选择特定实现的场景</li>
 *   <li>可以标注在类或注解类型上</li>
 *   <li>支持元注解，可在自定义注解上使用</li>
 * </ul>
 * <p>
 * 优先级示例：
 * <ul>
 *   <li>高优先级：100、50 等正数，优先加载</li>
 *   <li>默认优先级：0</li>
 *   <li>低优先级：-50、-100 等负数，延后加载</li>
 *   <li>相同优先级时，按类名字母顺序加载</li>
 * </ul>
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 示例 1：单独使用 SpiOrder 指定优先级
 * @Spi("redis")
 * @SpiOrder(100)
 * public class RedisCache implements Cache { }
 *
 * // 示例 2：同时使用 Spi 的 order 和 SpiOrder，SpiOrder 优先级更高
 * @Spi(value = "memory", order = 0)
 * @SpiOrder(50)
 * public class MemoryCache implements Cache { }
 *
 * // 示例 3：在接口上使用
 * @SpiOrder(0)
 * public interface ImageCorrector { }
 * }</pre>}
 * }</pre>
 * <p>
 * 优先级规则说明：
 * <ul>
 *   <li>当同时存在 {@link Spi#order()} 和 {@link SpiOrder} 时，以 {@link SpiOrder} 的值为准</li>
 *   <li>当只存在 {@link SpiOrder} 时，以 {@link SpiOrder} 的值为准</li>
 *   <li>当都不存在时，默认优先级为 0</li>
 * </ul>
 *
   * @版本 1.0.0
 * @author CH
 * @since 2025/01/22
 * @see Spi
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
public @interface SpiOrder {

    /**
     * 指定 SPI 实现的优先级顺序
     * <p>
     * 数值越大优先级越高，越先被加载。
     * 默认值为 0，表示默认优先级。
     *
     * @return 优先级数值，默认为 0
     */
    int value() default 0;
}

