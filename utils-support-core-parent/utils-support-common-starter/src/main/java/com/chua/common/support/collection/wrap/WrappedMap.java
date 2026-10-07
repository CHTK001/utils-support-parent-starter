package com.chua.common.support.collection.wrap;

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 映射分装对象：内部持有可切换的映射存储（默认 Eclipse {@code UnifiedMap}，
 * classpath 缺失时为 {@code HashMap}），对外表现为标准 {@link Map}。
 *
 * <p>本类继承 {@link AbstractMap}，{@code equals} / {@code hashCode} / {@code toString}
 * 按 {@code Map} 契约基于条目内容计算，与 JDK / Eclipse 存储互为等值；
 * 高频方法（get / put / 条目视图 / Java 8 函数式方法）全部直接委托底层存储。
 * UnifiedMap 将 key/value 内联进单表，较 HashMap 省约 13% 内存
 * （8 条目小映射场景约 54%），是分装体系的主要收益来源之一。</p>
 *
 * <p>{@link #entrySet()} / {@link #keySet()} / {@link #values()} 返回底层存储的
 * 实时视图（与 HashMap 行为一致），对视图的结构性修改同样作用于本映射。</p>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author CH
 * @since 4.0.0.42
 * @see Wraps#map()
 */
public class WrappedMap<K, V> extends AbstractMap<K, V> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 底层存储（eclipse UnifiedMap 或 JDK HashMap）。
     */
    private final Map<K, V> delegate;

    /**
     * 以指定映射为存储进行分装。
     *
     * @param delegate 底层存储，不可为 {@code null}
     */
    public WrappedMap(Map<K, V> delegate) {
        if (delegate == null) {
            throw new NullPointerException("delegate");
        }
        this.delegate = delegate;
    }

    /**
     * 取出底层存储（用于需要原始类型（如 {@code UnifiedMap}）的互操作场景）。
     *
     * @return 底层存储
     */
    public Map<K, V> delegate() {
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
}
