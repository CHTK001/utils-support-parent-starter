package com.chua.common.support.collection;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 多值 Map 接口，允许同一个 Key 关联多个 Value。
* <p>
* 与标准 {@link java.util.Map} 不同，MultiValueMap 的每个 Key 可以关联一个值列表，
* 适用于需要存储一对多关系的场景（如 HTTP 请求参数、多值属性配置等）。
* </p>
*
* @param <K> Key 类型
* @param <V> Value 类型
* @author CH
* @version 1.0.0
 */
public interface MultiValueMap<K, V> {
    /**
    * 获取指定 Key 关联的第一个值。
    *
    * @param key 要查询的 Key
    * @return 第一个值，如果不存在则返回 null
    */
    V getFirst(K key);

    /**
    * 向指定 Key 添加一个值（追加到值列表末尾）。
    *
    * @param key   要添加的 Key
    * @param value 要添加的值
    */
    default void put(K key, V value) {
        add(key, value);
    }

    /**
    * 向指定 Key 批量添加多个值（数组形式）。
    *
    * @param key   要添加的 Key
    * @param value 值数组，每个元素依次添加
    */
    default void put(K key, V[] value) {
        for (V v : value) {
            put(key, v);
        }
    }

    /**
    * 向指定 Key 添加一个值（追加到值列表末尾）。
    *
    * @param key   要添加的 Key
    * @param value 要添加的值
    */
    void add(K key, V value);

    /**
    * 向指定 Key 批量添加多个值（委托给 {@link #addAll(Object, List)}）。
    *
    * @param key    要添加的 Key
    * @param values 要添加的值列表
    */
    default void putAll(K key, List<V> values) {
        addAll(key, values);
    }

    /**
    * 向指定 Key 批量添加多个值。
    *
    * @param key    要添加的 Key
    * @param values 要添加的值列表
    */
    void addAll(K key, List<V> values);

    /**
    * 批量添加另一个 MultiValueMap 中的所有键值对。
    *
    * @param values 源 MultiValueMap
    */
    void addAll(MultiValueMap<K, V> values);

    /**
    * 如果指定 Key 不存在，则添加一个值；否则不执行任何操作。
    *
    * @param key   要检查并添加的 Key
    * @param value 要添加的值
    * @since 5.2
    */
    default void addIfAbsent(K key, V value) {
        if (!containsKey(key)) {
            add(key, value);
        }
    }

    /**
    * 判断 Map 中是否包含指定的 Key。
    *
    * @param key 要检查的 Key
    * @return 如果包含则返回 true，否则返回 false
    */
    boolean containsKey(K key);

    /**
    * 设置指定 Key 的值（覆盖该 Key 的现有值列表为仅包含指定值的单元素列表）。
    *
    * @param key   要设置的 Key
    * @param value 新的值
    */
    void set(K key, V value);

    /**
    * 通过普通 Map 批量设置值，覆盖已有 Key 的值列表为单元素列表。
    *
    * @param values 包含键值对的普通 Map
    */
    void setAll(Map<K, V> values);

    /**
    * 将此 MultiValueMap 转换为单值 Map，只保留每个 Key 的第一个值。
    *
    * @return 转换后的单值 Map，不会返回 null
    */
    Map<K, V> toSingleValueMap();

    /**
    * 返回所有 Key-值列表映射的 Entry 集合。
    *
    * @return Entry 集合，每个 Entry 的值为 {@code List<V>}
    */
    Set<Map.Entry<K, List<V>>> entrySet();

    /**
    * 遍历所有键值对（同一个 Key 的多个值会分别回调 Consumer）。
    *
    * @param consumer 消费函数，接收 Key 和 Value
    */
    void forEach(BiConsumer<K, V> consumer);

    /**
    * 判断 Map 是否为空。
    *
    * @return 如果没有任何键值对则返回 true，否则返回 false
    */
    boolean isEmpty();

    /**
    * 返回所有值的平铺列表，将所有 Key 对应的值列表合并为一个列表。
    *
    * @return 值的平铺列表
    */
    List<V> values();

    /**
    * 返回所有 Key 的集合。
    *
    * @return Key 集合
    */
    Set<K> keySet();

    /**
    * 获取指定 Key 关联的所有值列表。
    *
    * @param key 要查询的 Key
    * @return 值列表，如果 Key 不存在则返回 null
    */
    List<V> get(K key);

    /**
    * 获取指定 Key 关联的最后一个值。
    *
    * @param key 要查询的 Key
    * @return 最后一个值，如果 Key 不存在则返回 null
    */
    V getOne(K key);

    /**
    * 移除指定 Key 及其所有关联的值。
    *
    * @param name 要移除的 Key
    */
    void remove(V name);
}
