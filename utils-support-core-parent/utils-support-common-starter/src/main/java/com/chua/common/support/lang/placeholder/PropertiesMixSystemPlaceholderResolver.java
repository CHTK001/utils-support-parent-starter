package com.chua.common.support.lang.placeholder;

import java.util.Properties;


/**
* 一个结合了自定义 Properties 和系统属性（System Properties）的占位符解析器。
* <p>
* 该类实现了 {@link PlaceholderResolver} 和 {@link PlaceholderDynamicResolver} 接口，
* 用于在字符串中替换占位符。其解析逻辑遵循以下优先级：
* <ol>
*   <li>首先尝试从传入的 {@code props} 属性集合中查找对应的值。</li>
*   <li>如果未在自定义属性中找到，则回退到系统属性（通过 {@link SystemPropertyPlaceholderResolver}）进行查找。</li>
* </ol>
* 此外，它还支持动态地添加或移除自定义属性。
*
* @author CH
* @since 4.0.0.42
 */
public class PropertiesMixSystemPlaceholderResolver implements PlaceholderResolver, PlaceholderDynamicResolver {

    /**
    * 存储自定义属性的核心容器。
     */
    private final Properties props;
    
    /**
    * 用于解析系统属性的辅助解析器实例。
     */
    private final SystemPropertyPlaceholderResolver placeholderResolver;

    /**
    * 构造函数，初始化自定义属性集合并创建系统属性解析器。
    *
    * @param props 需要被包含在解析逻辑中的自定义属性集合。
     */
    public PropertiesMixSystemPlaceholderResolver(Properties props) {
        this.props = props;
        // 初始化内部使用的系统属性解析器
        this.placeholderResolver = new SystemPropertyPlaceholderResolver();
    }

    /**
    * 解析指定的占位符名称。
    * <p>
    * 解析策略：
    * 1. 优先在自定义 {@code props} 中查找键值对。
    * 2. 若未找到，则委托给系统属性解析器继续查找。
    *
    * @param placeholderName 要解析的占位符名称（即属性键）。
    * @return 解析后的字符串值；如果均不存在，通常返回 null 或空字符串（取决于底层实现，此处返回 resolvePlaceholder 的结果）。
     */
    @Override
    public String resolvePlaceholder(String placeholderName) {
        Object property = props.get(placeholderName);
        if (null == property) {
            // 自定义属性未命中，回退到系统属性
            return placeholderResolver.resolvePlaceholder(placeholderName);
        }

        // 将找到的对象转换为字符串返回
        return property.toString();
    }

    /**
    * 获取指定键的属性值。
    * <p>
    * 此方法作为便捷方法，直接调用 {@link #resolvePlaceholder(String)} 进行实际解析。
    *
    * @param key 属性键。
    * @return 对应的属性值字符串。
     */
    @Override
    public String getProperty(String key) {
        return resolvePlaceholder(key);
    }

    /**
    * 向当前的自定义属性集中动态添加一个新的键值对。
    * <p>
    * 如果值为 null，则不会执行添加操作。
    *
    * @param name  要添加的属性名。
    * @param value 要添加的属性值。
    * @return 返回当前实例本身，以支持链式调用。
     */
    @Override
    public PlaceholderDynamicResolver add(String name, Object value) {
        if (null != value) {
            props.put(name, value);
        }
        return this;
    }

    /**
    * 从当前的自定义属性集中移除指定名称的属性。
    *
    * @param name 要移除的属性名。
     */
    @Override
    public void remove(String name) {
        props.remove(name);
    }
}
