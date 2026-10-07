package com.chua.common.support.collection.wrap;

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 有序映射分装对象：内部持有可切换的有序映射存储，对外表现为标准 {@link SortedMap}。
 *
 * <p><b>存储说明：</b>与 {@link WrappedSortedSet} 相同：Eclipse Collections 的
 * TreeSortedMap 内部即 JDK TreeMap 薄壳，两内置后端均直接返回 {@link java.util.TreeMap}
 * 存储，本类型以统一入口（{@link Wraps#sortedMap()}）与后端切换点为价值；
 * 需要导航操作时使用 {@link Wraps#navigableMap()}。</p>
 *
 * <p>本类继承 {@link AbstractMap} 实现 {@link SortedMap}，{@code equals} /
 * {@code hashCode} 按 {@code Map} 契约基于条目内容计算；区间视图
 * （headMap/tailMap/subMap）同样返回分装对象并共享底层存储。</p>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author CH
 * @since 4.0.0.42
 * @see Wraps#sortedMap()
 * @see NavigableMap
 */
public class WrappedSortedMap<K, V> extends AbstractMap<K, V> implements SortedMap<K, V>, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 底层存储（两后端均为 JDK TreeMap）。
     */
    private final SortedMap<K, V> delegate;

    /**
     * 以指定有序映射为存储进行分装。
     *
     * @param delegate 底层存储，不可为 {@code null}
     */
    public WrappedSortedMap(SortedMap<K, V> delegate) {
        if (delegate == null) {
            throw new NullPointerException("delegate");
        }
        this.delegate = delegate;
    }

    /**
     * 取出底层存储。
     *
     * @return 底层存储
     */
    public SortedMap<K, V> delegate() {
        return delegate;
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public boolean containsKey(Object key) {
        return delegate.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return delegate.containsValue(value);
    }

    @Override
    public V get(Object key) {
        return delegate.get(key);
    }

    @Override
    public V put(K key, V value) {
        return delegate.put(key, value);
    }

    @Override
    public V remove(Object key) {
        return delegate.remove(key);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        delegate.putAll(m);
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return delegate.entrySet();
    }

    @Override
    public Set<K> keySet() {
        return delegate.keySet();
    }

    @Override
    public Collection<V> values() {
        return delegate.values();
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        return delegate.getOrDefault(key, defaultValue);
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        delegate.forEach(action);
    }

    @Override
    public V putIfAbsent(K key, V value) {
        return delegate.putIfAbsent(key, value);
    }

    @Override
    public boolean remove(Object key, Object value) {
        return delegate.remove(key, value);
    }

    @Override
    public V replace(K key, V value) {
        return delegate.replace(key, value);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        return delegate.replace(key, oldValue, newValue);
    }

    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        delegate.replaceAll(function);
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        return delegate.computeIfAbsent(key, mappingFunction);
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        return delegate.computeIfPresent(key, remappingFunction);
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        return delegate.compute(key, remappingFunction);
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        return delegate.merge(key, value, remappingFunction);
    }

    // ============================= SortedMap ================================

    @Override
    public Comparator<? super K> comparator() {
        return delegate.comparator();
    }

    @Override
    public K firstKey() {
        return delegate.firstKey();
    }

    @Override
    public K lastKey() {
        return delegate.lastKey();
    }

    @Override
    public SortedMap<K, V> headMap(K toKey) {
        return new WrappedSortedMap<>(delegate.headMap(toKey));
    }

    @Override
    public SortedMap<K, V> tailMap(K fromKey) {
        return new WrappedSortedMap<>(delegate.tailMap(fromKey));
    }

    @Override
    public SortedMap<K, V> subMap(K fromKey, K toKey) {
        return new WrappedSortedMap<>(delegate.subMap(fromKey, toKey));
    }
}
