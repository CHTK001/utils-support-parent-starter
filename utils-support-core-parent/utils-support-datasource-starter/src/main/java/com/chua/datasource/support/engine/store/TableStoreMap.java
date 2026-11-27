package com.chua.datasource.support.engine.store;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 引擎表容器：{@code 表名 -> 行列表}，把真实承载交给 {@link TableStore}。
 *
 * <p>历史实现是裸 {@code ConcurrentHashMap<String, List<?>>}。本类保持同一套
 * {@link Map} 契约，使引擎侧调用点无需改动，同时把「行放在哪里」变成可替换能力：
 * 默认 {@link HeapTableStore} 与历史行为完全一致，显式切换才使用
 * {@link MappedFileTableStore}。</p>
 *
 * <h2>视图权威</h2>
 * <p>内部 {@code view} 是「该表是否存在」的唯一权威：{@link #get(Object)}、
 * {@link #containsKey(Object)}、{@link #size()}、{@link #keySet()} 等只读视图，
 * 不会因为探测未命中而让存储实现顺手建表。{@link #computeIfAbsent} 是引擎
 * 显式「建/取表」的入口，此时才调 {@link TableStore#open(String)}。</p>
 *
 * <h2>引用同一性</h2>
 * <p>引擎会用 {@code entry.getValue() == data} 判断「表是否被整体替换」，
 * 因此同一表名必须稳定返回同一列表实例，{@link #put} 后视图里的值也必须
 * 等于 {@link TableStore#open(String)} 的返回值。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public class TableStoreMap implements Map<String, List<?>> {

    /**
     * 存储实现。
     */
    private volatile TableStore store;

    /**
     * 表名 → 行列表（权威视图）。
     */
    private final Map<String, List<?>> view = new ConcurrentHashMap<>();

    /**
     * 使用默认（堆）存储。
     */
    public TableStoreMap() {
        this(new HeapTableStore());
    }

    /**
     * 指定存储实现。
     *
     * @param store 存储实现
     */
    public TableStoreMap(TableStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 当前存储实现。
     *
     * @return 存储实现
     */
    public TableStore store() {
        return store;
    }

    /**
     * 切换存储实现，并把已装载的表数据迁移过去。
     *
     * <p>迁移顺序是「先快照 → 再写新存储 → 最后关旧存储」，任何一步失败都不会
     * 让已有数据凭空消失；新存储写入失败会保持旧存储不动并抛出异常。</p>
     *
     * <p>「两个实现是否可以互相替代」由 {@link TableStore#sameStore(TableStore)} 判定：
     * 只有等价实现才直接返回旧实例；实现名相同但实例级配置不同（如 mmap 的存储目录）
     * 必须真实迁移，否则显式切换会静默失效。</p>
     *
     * @param replacement 新存储实现
     * @return 被替换下来的旧存储
     */
    public synchronized TableStore switchStore(TableStore replacement) {
        Objects.requireNonNull(replacement, "replacement");
        TableStore previous = this.store;
        if (previous.sameStore(replacement)) {
            return previous;
        }
        List<String> keys = new ArrayList<>(view.keySet());
        List<List<Object>> snapshots = new ArrayList<>(keys.size());
        for (String key : keys) {
            snapshots.add(new ArrayList<>(castRows(view.get(key))));
        }
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            List<Object> rows = snapshots.get(i);
            replacement.replace(key, rows);
            view.put(key, replacement.open(key));
        }
        this.store = replacement;
        for (String key : keys) {
            previous.closeTable(key);
        }
        return previous;
    }

    @Override
    public int size() {
        return view.size();
    }

    @Override
    public boolean isEmpty() {
        return view.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return view.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return view.containsValue(value);
    }

    @Override
    public List<?> get(Object key) {
        return view.get(key);
    }

    /**
     * 整体替换一张表。
     *
     * <p>先让存储实现接手新内容，再把存储返回的稳定实例放进视图，
     * 因此调用方拿到的 {@code get} 结果与 {@code put} 传入的内容一致，
     * 后续 {@code entry.getValue() == data} 的引用判断仍然有效。</p>
     *
     * @param key   表名
     * @param value 行集合
     * @return 替换前的行集合
     */
    @Override
    public List<?> put(String key, List<?> value) {
        Objects.requireNonNull(key, "key");
        List<?> previous = view.get(key);
        store.replace(key, castRows(value));
        view.put(key, store.open(key));
        return previous;
    }

    /**
     * 取表，不存在时开表。映射函数被忽略：行的承载形态由存储实现决定。
     *
     * @param key             表名
     * @param mappingFunction 忽略
     * @return 该表的行列表
     */
    @Override
    public List<?> computeIfAbsent(String key, Function<? super String, ? extends List<?>> mappingFunction) {
        Objects.requireNonNull(key, "key");
        List<?> rows = view.get(key);
        if (rows != null) {
            return rows;
        }
        rows = store.open(key);
        view.put(key, rows);
        return rows;
    }

    @Override
    public List<?> getOrDefault(Object key, List<?> defaultValue) {
        List<?> rows = view.get(key);
        return rows != null ? rows : defaultValue;
    }

    /**
     * 摘除一张表：视图先移除，再让存储实现释放并删除该表的资源。
     *
     * @param key 表名
     * @return 被摘除的行集合
     */
    @Override
    public List<?> remove(Object key) {
        List<?> removed = view.remove(key);
        if (removed != null && key instanceof String name) {
            store.closeTable(name);
        }
        return removed;
    }

    @Override
    public void putAll(Map<? extends String, ? extends List<?>> map) {
        for (Map.Entry<? extends String, ? extends List<?>> entry : map.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 清空全部表并关闭存储。
     *
     * <p>只在引擎关闭时调用：存储实现的 {@link TableStore#close()} 负责把可持久化的
     * 内容落盘并释放映射；逐表摘除才走 {@link #remove(Object)}。</p>
     */
    @Override
    public void clear() {
        view.clear();
        store.close();
    }

    @Override
    public Set<String> keySet() {
        return view.keySet();
    }

    @Override
    public Collection<List<?>> values() {
        return view.values();
    }

    @Override
    public Set<Entry<String, List<?>>> entrySet() {
        return view.entrySet();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Map<?, ?> map && view.equals(map);
    }

    @Override
    public int hashCode() {
        return view.hashCode();
    }

    @Override
    public String toString() {
        return view.toString();
    }

    /**
     * 行集合的擦除转换（引擎内部全部按 {@code List<Object>} 处理）。
     *
     * @param rows 行集合
     * @return 同一实例
     */
    @SuppressWarnings("unchecked")
    private static List<Object> castRows(List<?> rows) {
        return (List<Object>) rows;
    }
}
