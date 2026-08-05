package com.chua.common.support.lang.placeholder;

import java.util.Map;


/**
 * 混合占位符解析器，优先从提供的 Map 中解析属性，若未找到则回退到系统属性解析。
 * <p>
 * 该类实现了动态属性添加和删除功能，支持在运行时修改配置映射。
 *
 * @author CH
 */
public class MapMixSystemPlaceholderResolver implements PlaceholderResolver, PlaceholderDynamicResolver {

    /**
     * 存储用户自定义属性的映射表，优先级高于系统属性。
     */
    private final Map<String, Object> props;
    
    /**
     * 用于解析系统属性（如 JVM 系统属性）的辅助解析器。
     */
    private final SystemPropertyPlaceholderResolver placeholderResolver;

    /**
     * 构造函数，初始化属性映射表和系统属性解析器。
     *
     * @param props 包含自定义键值对的 Map，用于提供优先级的占位符值。
     */
    public MapMixSystemPlaceholderResolver(Map<String, Object> props) {
        this.props = props;
        this.placeholderResolver = new SystemPropertyPlaceholderResolver();
    }

    /**
     * 解析占位符名称对应的值。
     * <p>
     * 解析逻辑：
     * 1. 首先尝试从 {@code props} 映射表中获取值。
     * 2. 如果映射表中不存在该键，则委托给 {@code placeholderResolver} 从系统属性中查找。
     * 3. 将找到的对象转换为字符串返回；若均未找到，则返回 null（取决于底层实现，此处假设返回 null 或原样）。
     *
     * @param placeholderName 需要解析的占位符名称（即键名）。
     * @return 解析后的字符串值；如果未找到任何匹配项，可能返回 null。
     */
    @Override
    public String resolvePlaceholder(String placeholderName) {
        if (placeholderName == null) {
            return null;
        }
        
        Object objects = props.get(placeholderName);
        if (objects != null) {
            return objects.toString();
        }

        // 回退到系统属性解析器
        return placeholderResolver.resolvePlaceholder(placeholderName);
    }

    /**
     * 获取指定键的属性值。
     * <p>
     * 此方法是对 {@link #resolvePlaceholder(String)} 的便捷封装。
     *
     * @param key 要获取的属性键。
     * @return 对应的属性值字符串。
     */
    @Override
    public String getProperty(String key) {
        return resolvePlaceholder(key);
    }

    /**
     * 向当前解析器中添加一个新的动态属性。
     * <p>
     * 添加的属性将覆盖或插入到 {@code props} 映射表中，并在后续解析时拥有最高优先级。
     *
     * @param name  属性的名称（键）。
     * @param value 属性的值。如果值为 null，则不执行添加操作。
     * @return 返回当前实例本身，以支持链式调用。
     */
    @Override
    public PlaceholderDynamicResolver add(String name, Object value) {
        if (name != null && value != null) {
            props.put(name, value);
        }
        return this;
    }

    /**
     * 从当前解析器中移除指定的属性。
     *
     * @param name 要移除的属性名称（键）。
     */
    @Override
    public void remove(String name) {
        if (name != null) {
            props.remove(name);
        }
    }
}