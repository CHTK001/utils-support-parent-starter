package com.chua.common.support.lang.placeholder;

import com.chua.common.support.collection.MultiValueMap;
import com.chua.common.support.lang.json.Json;

import java.util.Collection;

/**
 * 多值映射混合系统占位符解析器。
 * <p>
 * 该类实现了 {@link PlaceholderResolver} 和 {@link PlaceholderDynamicResolver} 接口，
 * 用于从自定义的多值映射（MultiValueMap）中解析占位符。如果指定的键在映射中不存在或为空，
 * 则回退到系统属性解析器进行查找。
 * <p>
 * 主要功能：
 * 1. 优先从传入的 {@code MultiValueMap} 中获取指定名称的值集合。
 * 2. 如果集合非空，将集合序列化为 JSON 字符串返回。
 * 3. 如果集合为空，则委托给内置的 {@code SystemPropertyPlaceholderResolver} 处理。
 * 4. 支持动态添加和移除占位符映射关系。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MultiMapMixSystemPlaceholderResolver implements PlaceholderResolver, PlaceholderDynamicResolver {

    /**
     * 存储用户自定义占位符映射的多值映射对象。
     * 允许一个键对应多个值。
     */
    private final MultiValueMap<String, Object> props;

    /**
     * 用于回退解析的系统属性占位符解析器。
     * 当自定义映射中未找到占位符时使用。
     */
    private final SystemPropertyPlaceholderResolver placeholderResolver;

    /**
     * 构造函数，初始化多值映射和系统属性解析器。
     *
     * @param props 包含自定义占位符及其值的多值映射对象。
     */
    public MultiMapMixSystemPlaceholderResolver(MultiValueMap<String, Object> props) {
        this.props = props;
        // 初始化默认的系统属性解析器，用于处理环境变量或系统属性中的占位符
        this.placeholderResolver = new SystemPropertyPlaceholderResolver();
    }

    /**
     * 解析指定的占位符名称。
     * <p>
     * 解析逻辑如下：
     * 1. 首先在 {@code props} 多值映射中查找该名称对应的值集合。
     * 2. 如果找到了非空集合，将该集合中的所有值转换为 JSON 格式并返回。
     * 3. 如果未找到或集合为空，则调用 {@code placeholderResolver} 尝试从系统属性中解析。
     *
     * @param placeholderName 需要解析的占位符名称（例如：${key} 中的 key）。
     * @return 解析后的字符串值；如果无法解析且系统属性中也找不到，通常返回 null 或原样字符串（取决于底层实现）。
     */
    @Override
    public String resolvePlaceholder(String placeholderName) {
        if (placeholderName == null || placeholderName.isEmpty()) {
            return null;
        }

        Collection<Object> objects = props.get(placeholderName);
        
        // 如果自定义映射中存在该键且有值，则序列化为 JSON
        if (objects != null && !objects.isEmpty()) {
            return Json.toJson(objects);
        }

        // 否则回退到系统属性解析器
        return placeholderResolver.resolvePlaceholder(placeholderName);
    }

    /**
     * 获取指定键的属性值。
     * <p>
     * 此方法是对 {@link #resolvePlaceholder(String)} 的别名调用，
     * 符合 {@link PlaceholderResolver} 接口的规范。
     *
     * @param key 要获取属性的键名。
     * @return 解析后的属性值字符串。
     */
    @Override
    public String getProperty(String key) {
        return resolvePlaceholder(key);
    }

    /**
     * 向映射中添加一个新的占位符键值对。
     * <p>
     * 如果值为 null，则不执行添加操作。该方法支持链式调用。
     *
     * @param name  占位符的名称（键）。
     * @param value 与名称关联的对象值。
     * @return 当前实例，以支持链式调用。
     */
    @Override
    public PlaceholderDynamicResolver add(String name, Object value) {
        if (name != null && value != null) {
            props.add(name, value);
        }
        return this;
    }

    /**
     * 从映射中移除指定的占位符键。
     *
     * @param name 要移除的占位符名称。
     */
    @Override
    public void remove(String name) {
        if (name != null) {
            props.remove(name);
        }
    }
}
