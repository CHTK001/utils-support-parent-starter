package com.chua.common.support.lang.bean;

import com.chua.common.support.lang.json.JsonPath;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;

/**
 * 基于 {@link JsonPath} 的 BeanPath 实现，将 JSON 字符串视为数据源。
 *
 * <p>当目标对象为 JSON 字符串时，使用 JSONPath 表达式进行查询。
 * 当目标对象为普通 Java 对象时，回退到 {@link ObjectBeanPath}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see JsonPath
 * @see BeanPath
 */
@Spi("json")
@ConditionalOnClass("com.chua.common.support.lang.json.JsonPath")
public class JsonPathBeanPath implements BeanPath {

    /**
     * 回退解析器
    */
    private final ObjectBeanPath fallback = new ObjectBeanPath();

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 获取Value
    */
    public <T> T getValue(Object source, String path) {
        if (source instanceof String json) {
            JsonPath jsonPath = JsonPath.getInstance();
            if (jsonPath != null) {
                return (T) jsonPath.read(json, path);
            }
        }
        return fallback.getValue(source, path);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 设置Value
    */
    public void setValue(Object source, String path, Object value) {
        if (source instanceof String json) {
            JsonPath jsonPath = JsonPath.getInstance();
            if (jsonPath != null) {
                jsonPath.set(json, path, value != null ? value.toString() : null);
                return;
            }
        }
        fallback.setValue(source, path, value);
    }

    @Override
    /**
     * 是否存在
    */
    public boolean exists(Object source, String path) {
        if (source instanceof String json) {
            JsonPath jsonPath = JsonPath.getInstance();
            return jsonPath != null && jsonPath.isExist(json, path);
        }
        return fallback.exists(source, path);
    }
}