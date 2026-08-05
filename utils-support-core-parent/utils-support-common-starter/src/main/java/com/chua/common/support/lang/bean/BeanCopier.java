package com.chua.common.support.lang.bean;

import java.util.Map;

/**
 * Bean 属性拷贝 SPI 接口，提供对象间属性复制功能。
 *
 * <p>该接口作为 SPI 扩展点，支持多种实现方式：
 * <ul>
 *   <li><b>JDK 实现（jdk）</b> — 基于反射，通用性强</li>
 *   <li><b>ASM 实现（asm）</b> — 基于字节码生成，性能最优</li>
 * </ul>
 * </p>
 *
 * <p>使用 {@link com.chua.common.support.spi.ServiceProvider} 获取实现实例：
 * <pre>{@code
 * BeanCopier copier = ServiceProvider.of(BeanCopier.class).getExtension("asm");
 * copier.copyProperties(source, target);
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 1.0.0
 */
public interface BeanCopier {

    /**
     * 将源对象的属性复制到目标对象中。
     * <p>
     * 仅复制源对象中存在的可读属性到目标对象中对应的可写属性。
     * 属性名和类型需匹配，类型不匹配时尝试转换。
     * </p>
     *
     * @param source 源对象
     * @param target 目标对象
     * @throws IllegalArgumentException 如果 source 或 target 为 null
     */
    void copyProperties(Object source, Object target);

    /**
     * 将源对象的属性复制到目标对象中，忽略指定属性。
     * <p>
     * 与 {@link #copyProperties(Object, Object)} 类似，但跳过 ignoreProperties 中的属性名。
     * </p>
     *
     * @param source           源对象
     * @param target           目标对象
     * @param ignoreProperties 要忽略的属性名数组
     * @throws IllegalArgumentException 如果 source 或 target 为 null
     */
    void copyProperties(Object source, Object target, String... ignoreProperties);

    /**
     * 将 Map 中的值复制到目标对象的属性中。
     * <p>
     * Map 的 key 对应属性名，value 对应属性值。
     * </p>
     *
     * @param sourceMap 源 Map
     * @param target    目标对象
     * @throws IllegalArgumentException 如果 sourceMap 或 target 为 null
     */
    void copyProperties(Map<String, Object> sourceMap, Object target);

    /**
     * 将源对象的属性复制到目标 Map 中。
     * <p>
     * 源对象的可读属性名作为 key，属性值作为 value 存入 Map。
     * </p>
     *
     * @param source 源对象
     * @param target 目标 Map
     * @throws IllegalArgumentException 如果 source 或 target 为 null
     */
    void copyProperties(Object source, Map<String, Object> target);
}
