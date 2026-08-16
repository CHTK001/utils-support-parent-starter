package com.chua.common.support.collection;

import com.chua.common.support.utils.CollectionUtils;

import java.io.Serializable;
import java.util.*;
import java.util.function.BiConsumer;


/**
 * 基于 LinkedHashMap 和 LinkedList 的多值 Map 实现。
 * <p>
 * 每个 Key 关联一个 {@link LinkedList} 值列表，保持 Key 的插入顺序和 Value 的添加顺序。
 * 通过 {@link LinkedHashMap} 保证 Key 的迭代顺序与插入顺序一致，
 * 每个 Key 对应的值列表使用 {@link LinkedList} 存储以支持高效的顺序访问。
 * </p>
 *
 * @param <K> Key 类型
 * @param <V> Value 类型
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 */
public class MultiLinkedValueMap<K, V> implements MultiValueMap<K, V>, Serializable {

    private final Map<K, List<V>> targetMap = new LinkedHashMap<>();

    /**
     * 构造一个空的 MultiLinkedValueMap 实例。
     */
    public MultiLinkedValueMap() {
    }

    /**
     * 根据普通 Map 构造 MultiLinkedValueMap 实例，将 Map 中的每个键值对转为多值存储。
     *
     * @param targetMap 普通 Map，每个条目中的值将作为单值添加到对应 Key 的值列表中
     */
    public MultiLinkedValueMap(Map<K, V> targetMap) {
        this();
        targetMap.forEach(this::add);
    }

    /**
     * 根据另一个 MultiValueMap 构造 MultiLinkedValueMap 实例，复制其所有键值对。
     *
     * @param targetMap 源 MultiValueMap
     */
    public MultiLinkedValueMap(MultiValueMap<K, V> targetMap) {
        addAll(targetMap);
    }

    /**
     * 获取指定 Key 关联的第一个值。
     *
     * @param key 要查询的 Key
     * @return 第一个值，如果 Key 不存在或值列表为空则返回 null
     */
    @Override
    public V getFirst(K key) {
        List<V> values = this.targetMap.get(key);
        return (values != null && !values.isEmpty() ? values.get(0) : null);
    }

    /**
     * 向指定 Key 的值列表末尾追加一个值。
     * <p>如果该 Key 尚无值列表，则自动创建一个新的 LinkedList。</p>
     *
     * @param key   要添加的 Key
     * @param value 要添加的值
     */
    @Override
    public void add(K key, V value) {
        List<V> values = this.targetMap.computeIfAbsent(key, k -> new LinkedList<>());
        values.add(value);
    }

    /**
     * 向指定 Key 批量添加多个值，追加到值列表末尾。
     *
     * @param key    要添加的 Key
     * @param values 要添加的值列表
     */
    @Override
    public void addAll(K key, List<V> values) {
        List<V> currentValues = this.targetMap.computeIfAbsent(key, k -> new LinkedList<>());
        currentValues.addAll(values);
    }

    /**
     * 从另一个 MultiValueMap 批量复制所有键值对，逐个 Key 追加值。
     *
     * @param values 源 MultiValueMap
     */
    @Override
    public void addAll(MultiValueMap<K, V> values) {
        for (Map.Entry<K, List<V>> entry : values.entrySet()) {
            addAll(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 设置指定 Key 的值，覆盖该 Key 的现有值列表为仅包含指定值的单元素列表。
     *
     * @param key   要设置的 Key
     * @param value 新的值
     */
    @Override
    public void set(K key, V value) {
        List<V> values = new LinkedList<>();
        values.add(value);
        this.targetMap.put(key, values);
    }

    /**
     * 通过普通 Map 批量设置值，覆盖已有 Key 的值列表。
     *
     * @param values 包含键值对的普通 Map
     */
    @Override
    public void setAll(Map<K, V> values) {
        values.forEach(this::set);
    }

    /**
     * 将此 MultiValueMap 转换为单值 Map，只保留每个 Key 的第一个值。
     *
     * @return 转换后的单值 LinkedHashMap，保持 Key 的插入顺序
     */
    @Override
    public Map<K, V> toSingleValueMap() {
        Map<K, V> singleValueMap = new LinkedHashMap<>();
        this.targetMap.forEach((key, values) -> {
            if (values != null && !values.isEmpty()) {
                singleValueMap.put(key, values.get(0));
            }
        });
        return singleValueMap;
    }

    /**
     * 返回所有 Key-值列表映射的 Entry 集合。
     *
     * @return Entry 集合，每个 Entry 的值为 {@code List<V>}
     */
    @Override
    public Set<Map.Entry<K, List<V>>> entrySet() {
        return targetMap.entrySet();
    }

    /**
     * 遍历所有键值对，同一个 Key 的多个值会分别回调 Consumer。
     *
     * @param consumer 消费函数，接收 Key 和 Value
     */
    @Override
    public void forEach(BiConsumer<K, V> consumer) {
        for (Map.Entry<K, List<V>> entry : targetMap.entrySet()) {
            K key = entry.getKey();
            entry.getValue().forEach(v -> {
                consumer.accept(key, v);
            });
        }
    }

    /**
     * 判断 Map 是否为空（没有任何 Key 值映射）。
     *
     * @return 如果为空返回 true，否则返回 false
     */
    @Override
    public boolean isEmpty() {
        return targetMap.isEmpty();
    }

    /**
     * 返回所有值的平铺列表，将所有 Key 对应的值列表合并为一个 LinkedList。
     *
     * @return 值的平铺列表
     */
    @Override
    public List<V> values() {
        Collection<List<V>> values = targetMap.values();
        List<V> rs = new LinkedList<>();
        for (List<V> value : values) {
            rs.addAll(value);
        }
        return rs;
    }

    /**
     * 返回所有 Key 的集合。
     *
     * @return Key 集合
     */
    @Override
    public Set<K> keySet() {
        return targetMap.keySet();
    }

    /**
     * 获取指定 Key 关联的所有值列表。
     *
     * @param key 要查询的 Key
     * @return 值列表，如果 Key 不存在则返回 null
     */
    @Override
    public List<V> get(K key) {
        return  targetMap.get(key);
    }

    /**
     * 获取指定 Key 关联的最后一个值。
     *
     * @param key 要查询的 Key
     * @return 最后一个值，如果 Key 不存在或值列表为空则返回 null
     */
    @Override
    public V getOne(K key) {
        return CollectionUtils.findLast(targetMap.get(key));
    }

    /**
     * 移除指定 Key 及其所有关联的值。
     *
     * @param name 要移除的 Key
     */
    @Override
    public void remove(V name) {
        targetMap.remove(name);
    }

    // ========== Map 接口实现 ==========

    /**
     * 判断 Map 中是否包含指定的 Key。
     *
     * @param key 要检查的 Key
     * @return 如果包含则返回 true，否则返回 false
     */
    @Override
    public boolean containsKey(Object key) {
        return this.targetMap.containsKey(key);
    }

    @Override
    public boolean equals(Object other) {
        return (this == other || this.targetMap.equals(other));
    }

    @Override
    public int hashCode() {
        return this.targetMap.hashCode();
    }

    @Override
    public String toString() {
        return this.targetMap.toString();
    }
}
