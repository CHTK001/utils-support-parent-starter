package com.chua.common.support.lang.placeholder;

import java.util.Properties;


/**
 * 基于 Java Properties 的属性占位符解析器实现。
 * <p>
 * 该类负责从给定的 {@link Properties} 对象中解析和获取属性值，
 * 实现了 {@link PlaceholderResolver} 接口，用于处理字符串中的占位符替换逻辑。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PropertyPlaceholderConfigurerResolver implements PlaceholderResolver {

    /**
     * 存储属性的核心容器。
     */
    private final Properties props;

    /**
     * 私有构造函数，通过传入的 Properties 对象初始化实例。
     *
     * @param props 包含配置属性的 Properties 对象，不能为 null。
     */
    private PropertyPlaceholderConfigurerResolver(Properties props) {
        this.props = props;
    }

    /**
     * 根据指定的占位符名称解析并返回对应的属性值。
     * <p>
     * 如果指定的 key 在 properties 中存在，则返回其对应的 value；
     * 如果不存在，则返回 null。
     * </p>
     *
     * @param placeholderName 需要解析的占位符名称（即属性键）。
     * @return 对应的属性值，若未找到则返回 null。
     */
    @Override
    public String resolvePlaceholder(String placeholderName) {
        return props.getProperty(placeholderName);
    }

    /**
     * 获取指定键的属性值，此方法作为便捷方法调用 resolvePlaceholder。
     *
     * @param key 要获取的属性键。
     * @return 对应的属性值，若未找到则返回 null。
     */
    @Override
    public String getProperty(String key) {
        return resolvePlaceholder(key);
    }
}
