package com.chua.common.support.config.source;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多属性源类，用于处理多个 Map 形式的配置属性。
 * <p>
 * 该类支持从多个 Map 中获取属性值，并合并所有属性。
 *
 * @author CH
 * @since 2023-09-07
 */
@Getter
public class PropertiesMutiPropertySource extends AbstractPropertySource implements MutiPropertySource {

    /**
     * 存储多个属性集合的可迭代对象。
     * 每个元素通常是一个包含键值对的 Map。
     */
    private final Iterable<?> properties;

    /**
     * 构造函数，初始化多属性源。
     *
     * @param name       属性源的名称。
     * @param properties 包含多个属性集合的可迭代对象。
     */
    public PropertiesMutiPropertySource(String name, Iterable<?> properties) {
        super(name);
        this.properties = properties;
    }

    @SuppressWarnings("ALL")
    @Override
    protected Object getRawProperty(String key) {
        if (properties == null) {
            return null;
        }
        for (Object property : properties) {
            if (property instanceof Map map && map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    @SuppressWarnings("ALL")
    @Override
    protected Object getSource() {
        if (properties == null) {
            return null;
        }
        // 合并所有 Map 中的属性到一个新的 LinkedHashMap 中
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Object property : properties) {
            if (property instanceof Map map) {
                merged.putAll(map);
            }
        }
        return merged;
    }
}
