package com.chua.common.support.task.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程节点属性容器。
 *
 * <p>封装流程图中节点配置参数的键值集合，提供类型安全读取方法。
 * 属性值来源于 JSON 图定义中节点的 {@code props} 字段，
 * 由引擎在构建流程时从 {@link FlowDefinition} 解析并注入到节点执行器。</p>
 *
 * <p>属性支持的类型包括字符串、整数、布尔值、小数、列表、映射等，
 * 读取时自动完成类型转换，避免节点执行器重复解析。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FlowProps {

    /**
     * 空属性容器，供无参数节点复用
     */
    public static final FlowProps EMPTY = new FlowProps(null);

    /**
     * 属性键值映射，保持插入顺序
     */
    private final Map<String, Object> values;

    /**
     * 构造属性容器。
     *
     * <p>入参为空时创建空映射，防止后续读取出现空指针。</p>
     *
     * @param values 属性键值映射
     */
    public FlowProps(Map<String, Object> values) {
        this.values = values != null ? new LinkedHashMap<>(values) : new LinkedHashMap<>();
    }

    /**
     * 从键值映射创建属性容器。
     *
     * @param values 属性键值映射
     * @return 属性容器实例
     */
    public static FlowProps of(Map<String, Object> values) {
        return new FlowProps(values);
    }

    /**
     * 判断指定属性是否存在。
     *
     * @param key 属性键
     * @return 存在返回 true，否则返回 false
     */
    public boolean has(String key) {
        return values.containsKey(key);
    }

    /**
     * 获取原始属性值。
     *
     * @param key 属性键
     * @return 属性值，不存在时返回 空
     */
    public Object get(String key) {
        return values.get(key);
    }

    /**
     * 获取字符串属性值。
     *
     * @param key 属性键
     * @return 字符串值，不存在或非字符串时返回 空
     */
    public String getString(String key) {
        Object value = values.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * 获取字符串属性值，带默认值。
     *
     * @param key          属性键
     * @param defaultValue 属性不存在时的默认值
     * @return 字符串值或默认值
     */
    public String getString(String key, String defaultValue) {
        String value = getString(key);
        return value != null ? value : defaultValue;
    }

    /**
     * 获取整型属性值。
     *
     * @param key 属性键
     * @return 整数值，不存在或转换失败时返回 空
     */
    public Integer getInt(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(String.valueOf(value));
    }

    /**
     * 获取整型属性值，带默认值。
     *
     * @param key          属性键
     * @param defaultValue 属性不存在或转换失败时的默认值
     * @return 整数值或默认值
     */
    public int getInt(String key, int defaultValue) {
        Integer value = getInt(key);
        return value != null ? value : defaultValue;
    }

    /**
     * 获取布尔属性值。
     *
     * @param key 属性键
     * @return 布尔值，不存在或转换失败时返回 空
     */
    public Boolean getBoolean(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.valueOf(String.valueOf(value));
    }

    /**
     * 获取小数属性值。
     *
     * @param key 属性键
     * @return 小数值，不存在或转换失败时返回 空
     */
    public Double getDouble(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.valueOf(String.valueOf(value));
    }

    /**
     * 获取列表属性值。
     *
     * <p>元素类型由调用方指定，常用于读取多值属性如 URL 列表。</p>
     *
     * @param key   属性键
     * @param clazz 元素类型
     * @param <T>   元素泛型
     * @return 列表值，不存在时返回空列表
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getList(String key, Class<T> clazz) {
        Object value = values.get(key);
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<T> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null && clazz.isAssignableFrom(item.getClass())) {
                result.add((T) item);
            } else if (item != null && clazz == String.class) {
                result.add((T) String.valueOf(item));
            }
        }
        return result;
    }

    /**
     * 获取字符串列表属性值。
     *
     * @param key 属性键
     * @return 字符串列表，不存在时返回空列表
     */
    public List<String> getStringList(String key) {
        return getList(key, String.class);
    }

    /**
     * 获取映射属性值。
     *
     * @param key 属性键
     * @return 映射值，不存在时返回空映射
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key) {
        Object value = values.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    /**
     * 获取全部属性键值映射。
     *
     * @return 不可修改的属性映射
     */
    public Map<String, Object> toMap() {
        return new LinkedHashMap<>(values);
    }

    /**
     * 获取属性数量。
     *
     * @return 属性个数
     */
    public int size() {
        return values.size();
    }
}
