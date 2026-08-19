package com.chua.common.support.collection;

import com.chua.common.support.converter.Converter;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量级原始 Map 封装
 *
 * <p>内部持有 {@code Map<String, Object>}，实现标准 Map 接口，
 * 同时提供类型安全的取值方法（基于 Converter 转换）。
 * 适用于协议解析结果、配置参数、JSON 反序列化等场景。
 *
 * <h3>嵌套取值</h3>
 * <pre>
 *   get("a.b.c")   → 精确匹配优先，找不到则嵌套穿透
 *   getDot("a.b.c") → 强制嵌套穿透
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   LiteRawMap map = LiteRawMap.of(rawMap);
 *
 *   // 类型安全取值（基于 Converter）
 *   String name = map.getString("name");
 *   int age = map.getInt("age", 0);
 *   boolean active = map.getBoolean("active", false);
 *
 *   // 嵌套取值
 *   String city = (String) map.getDot("address.city");
 *
 *   // 扁平化
 *   Map<String, Object> flat = map.flatten();
 * }</pre>
 *
 * @author CH
 * @since 2026/07/17
 */
@SuppressWarnings("unchecked")
public class LiteRawMap implements Map<String, Object> {

    /**
     * 内部存储的 Map 数据
     */
    private final Map<String, Object> delegate;

    /**
     * 从原始 Map 创建 LiteRawMap
     *
     * @param map 原始 Map 数据
     * @return LiteRawMap 实例
     */
    public static LiteRawMap of(Map<String, Object> map) {
        if (map instanceof LiteRawMap lrm) {
            return lrm;
        }
        return new LiteRawMap(map != null ? map : new LinkedHashMap<>());
    }

    /**
     * 创建空的 LiteRawMap
     *
     * @return 空的 LiteRawMap 实例
     */
    public static LiteRawMap create() {
        return new LiteRawMap(new LinkedHashMap<>());
    }

    /**
     * 构造方法
     *
     * @param delegate 内部存储的 Map
     */
    private LiteRawMap(Map<String, Object> delegate) {
        this.delegate = delegate;
    }

    // ==================== 嵌套取值 ====================

    /**
     * 嵌套取值（强制穿透）
     *
     * <p>按点号分隔路径，逐级穿透嵌套 Map。
     * 例：getDot("a.b.c") → delegate["a"]["b"]["c"]
     *
     * @param dotPath 点号分隔的路径
     * @return 对应的值，路径不存在则返回 null
     */
    public Object getDot(String dotPath) {
        String[] parts = dotPath.split("\\.");
        Object current = delegate;
        for (String part : parts) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * 嵌套取值（类型安全）
     *
     * @param dotPath 点号分隔的路径
     * @param type    目标类型
     * @return 转换后的值
     */
    public <T> T getDot(String dotPath, Class<T> type) {
        Object val = getDot(dotPath);
        if (val == null) {
            return null;
        }
        if (type.isInstance(val)) {
            return type.cast(val);
        }
        return Converter.convertIfNecessary(val, type);
    }

    // ==================== 类型安全取值（基于 Converter） ====================

    /**
     * 获取字符串值
     *
     * @param key 属性键
     * @return 字符串值，不存在则返回 null
     */
    public String getString(String key) {
        return Converter.convertIfNecessary(get(key), String.class);
    }

    /**
     * 获取字符串值（带默认值）
     *
     * @param key 属性键
     * @param def 默认值
     * @return 字符串值或默认值
     */
    public String getString(String key, String def) {
        return Converter.convertIfNecessary(get(key), String.class, def);
    }

    /**
     * 获取整数值（带默认值）
     *
     * @param key 属性键
     * @param def 默认值
     * @return 整数值或默认值
     */
    public int getInt(String key, int def) {
        return Converter.convertIfNecessary(get(key), Integer.class, def);
    }

    /**
     * 获取长整型值（带默认值）
     *
     * @param key 属性键
     * @param def 默认值
     * @return 长整型值或默认值
     */
    public long getLong(String key, long def) {
        return Converter.convertIfNecessary(get(key), Long.class, def);
    }

    /**
     * 获取双精度值（带默认值）
     *
     * @param key 属性键
     * @param def 默认值
     * @return 双精度值或默认值
     */
    public double getDouble(String key, double def) {
        return Converter.convertIfNecessary(get(key), Double.class, def);
    }

    /**
     * 获取布尔值（带默认值）
     *
     * @param key 属性键
     * @param def 默认值
     * @return 布尔值或默认值
     */
    public boolean getBoolean(String key, boolean def) {
        return Converter.convertIfNecessary(get(key), Boolean.class, def);
    }

    /**
     * 获取 BigDecimal 值
     *
     * @param key 属性键
     * @return BigDecimal 值，不存在则返回 null
     */
    public BigDecimal getBigDecimal(String key) {
        return Converter.convertIfNecessary(get(key), BigDecimal.class);
    }

    // ==================== 泛型取值 ====================

    /**
     * 获取 List 值
     *
     * @param key 属性键
     * @return List 值，不存在则返回 null
     */
    public <T> List<T> getList(String key) {
        Object val = delegate.get(key);
        if (val instanceof List<?> list) {
            return (List<T>) list;
        }
        return null;
    }

    /**
     * 获取嵌套 Map 值
     *
     * @param key 属性键
     * @return 嵌套 Map，不存在则返回 null
     */
    public Map<String, Object> getMap(String key) {
        Object val = delegate.get(key);
        if (val instanceof Map<?, ?> map) {
            return (Map<String, Object>) (Object) map;
        }
        return null;
    }

    // ==================== 扁平化 ====================

    /**
     * 扁平化嵌套 Map
     *
     * <p>将多层嵌套的 Map 展开为点号分隔的扁平结构。
     * 例：{server: {port: 8080}} → {"server.port": 8080}
     *
     * @return 扁平化后的 Map
     */
    public Map<String, Object> flatten() {
        Map<String, Object> result = new LinkedHashMap<>();
        flattenInternal(delegate, "", result);
        return result;
    }

    /**
     * 递归扁平化
     */
    private void flattenInternal(Map<String, ?> map, String prefix, Map<String, Object> result) {
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object val = entry.getValue();
            if (val instanceof Map<?, ?> nested) {
                flattenInternal((Map<String, ?>) nested, key, result);
            } else {
                result.put(key, val);
            }
        }
    }

    // ==================== Map 接口实现 ====================

    @Override
    /** 获取大小 */
    public int size() {
        return delegate.size();
    }

    @Override
    /** 是否Empty */
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    /** ContainsKey */
    public boolean containsKey(Object key) {
        return delegate.containsKey(key);
    }

    @Override
    /** ContainsValue */
    public boolean containsValue(Object value) {
        return delegate.containsValue(value);
    }

    @Override
    /** 获取 */
    public Object get(Object key) {
        return delegate.get(key);
    }

    @Override
    /** Put */
    public Object put(String key, Object value) {
        return delegate.put(key, value);
    }

    @Override
    /** 移除 */
    public Object remove(Object key) {
        return delegate.remove(key);
    }

    @Override
    /** PutAll */
    public void putAll(Map<? extends String, ?> m) {
        delegate.putAll(m);
    }

    @Override
    /** Clear */
    public void clear() {
        delegate.clear();
    }

    @Override
    /** Key设置 */
    public Set<String> keySet() {
        return delegate.keySet();
    }

    @Override
    /** Values */
    public Collection<Object> values() {
        return delegate.values();
    }

    @Override
    /** Entry设置 */
    public Set<Map.Entry<String, Object>> entrySet() {
        return delegate.entrySet();
    }

    @Override
    /** ToString */
    public String toString() {
        return delegate.toString();
    }
}