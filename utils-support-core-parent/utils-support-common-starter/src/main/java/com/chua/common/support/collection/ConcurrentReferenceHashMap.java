package com.chua.common.support.collection;

import com.chua.common.support.utils.ObjectUtils;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;


/**
 * 支持软引用/弱引用的并发 HashMap 实现。
 * <p>
 * 基于分段锁（Segment）设计，将整个 Map 划分为多个独立的段，每个段独立加锁，
 * 从而提供高并发的读写性能。
 * </p>
 * <p>
 * 与普通的 {@link ConcurrentHashMap} 不同，本实现的值通过
 * {@link java.lang.ref.SoftReference} 或 {@link java.lang.ref.WeakReference} 包装，
 * 在内存不足时 GC 可以回收不再被强引用的条目，从而避免内存泄漏，
 * 适用于缓存场景。
 * </p>
 *
 * @param <K> Key 类型
 * @param <V> Value 类型
 * @author Spring Framework
 * @author CH
 * @version 1.0.0
 */
@SuppressWarnings({"NullAway", "unchecked", "serial", "rawtypes"})
@NullUnmarked
public class ConcurrentReferenceHashMap<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {

    private static final int DEFAULT_INITIAL_CAPACITY = 16;

    private static final float DEFAULT_LOAD_FACTOR = 0.75f;

    private static final int DEFAULT_CONCURRENCY_LEVEL = 16;

    private static final ReferenceType DEFAULT_REFERENCE_TYPE = ReferenceType.SOFT;

    private static final int MAXIMUM_CONCURRENCY_LEVEL = 1 << 16;

    private static final int MAXIMUM_SEGMENT_SIZE = 1 << 30;


    /**
     * 分段数组，使用哈希值的高位进行索引。
     */
    private final Segment[] segments;

    /**
     * 负载因子，当每个表平均引用数超过此值时触发扩容。
     */
    private final float loadFactor;

    /**
     * 引用类型：SOFT（软引用）或 WEAK（弱引用）。
     */
    private final ReferenceType referenceType;

    /**
     * 移位值，用于计算分段数组大小以及从哈希值中提取索引。
     */
    private final int shift;

    /**
     * 延迟绑定的 Entry 集合视图。
     */

    private volatile Set<Map.Entry<K, V>> entrySet;


    /**
     * 创建一个新的 {@code ConcurrentReferenceHashMap} 实例。
     */
    public ConcurrentReferenceHashMap() {
        this(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR, DEFAULT_CONCURRENCY_LEVEL, DEFAULT_REFERENCE_TYPE);
    }

    /**
     * 创建一个新的 {@code ConcurrentReferenceHashMap} 实例。
     *
     * @param initialCapacity 初始容量
     */
    public ConcurrentReferenceHashMap(int initialCapacity) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR, DEFAULT_CONCURRENCY_LEVEL, DEFAULT_REFERENCE_TYPE);
    }

    /**
     * 创建一个新的 {@code ConcurrentReferenceHashMap} 实例。
     *
     * @param initialCapacity 初始容量
     * @param loadFactor      负载因子，当每个表平均引用数超过此值时触发扩容
     */
    public ConcurrentReferenceHashMap(int initialCapacity, float loadFactor) {
        this(initialCapacity, loadFactor, DEFAULT_CONCURRENCY_LEVEL, DEFAULT_REFERENCE_TYPE);
    }

    /**
     * 创建一个新的 {@code ConcurrentReferenceHashMap} 实例。
     *
     * @param initialCapacity  初始容量
     * @param concurrencyLevel 预期的并发写入线程数
     */
    public ConcurrentReferenceHashMap(int initialCapacity, int concurrencyLevel) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR, concurrencyLevel, DEFAULT_REFERENCE_TYPE);
    }

    /**
     * 创建一个新的 {@code ConcurrentReferenceHashMap} 实例。
     *
     * @param initialCapacity 初始容量
     * @param referenceType   条目的引用类型（软引用或弱引用）
     */
    public ConcurrentReferenceHashMap(int initialCapacity, ReferenceType referenceType) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR, DEFAULT_CONCURRENCY_LEVEL, referenceType);
    }

    /**
     * 创建一个新的 {@code ConcurrentReferenceHashMap} 实例。
     *
     * @param initialCapacity  初始容量
     * @param loadFactor       负载因子，当每个表平均引用数超过此值时触发扩容
     * @param concurrencyLevel 预期的并发写入线程数
     */
    public ConcurrentReferenceHashMap(int initialCapacity, float loadFactor, int concurrencyLevel) {
        this(initialCapacity, loadFactor, concurrencyLevel, DEFAULT_REFERENCE_TYPE);
    }

    /**
     * 创建一个新的 {@code ConcurrentReferenceHashMap} 实例。
     *
     * @param initialCapacity  初始容量
     * @param loadFactor       负载因子，当每个表平均引用数超过此值时触发扩容
     * @param concurrencyLevel 预期的并发写入线程数
     * @param referenceType    条目的引用类型（软引用或弱引用）
     */
    public ConcurrentReferenceHashMap(
            int initialCapacity, float loadFactor, int concurrencyLevel, ReferenceType referenceType) {

        this.loadFactor = loadFactor;
        this.shift = calculateShift(concurrencyLevel, MAXIMUM_CONCURRENCY_LEVEL);
        int size = 1 << this.shift;
        this.referenceType = referenceType;
        int roundedUpSegmentCapacity = (int) ((initialCapacity + size - 1L) / size);
        int initialSize = 1 << calculateShift(roundedUpSegmentCapacity, MAXIMUM_SEGMENT_SIZE);
        Segment[] segments = (Segment[]) Array.newInstance(Segment.class, size);
        int resizeThreshold = (int) (initialSize * getLoadFactor());
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new Segment(initialSize, resizeThreshold);
        }
        this.segments = segments;
    }


    protected final float getLoadFactor() {
        return this.loadFactor;
    }

    protected final int getSegmentsSize() {
        return this.segments.length;
    }

    protected final Segment getSegment(int index) {
        return this.segments[index];
    }

    /**
     * 工厂方法，返回 {@link ReferenceManager} 引用管理器。
     * 每个 {@link Segment} 段对应一个引用管理器实例。
     *
     * @return 新的引用管理器
     */
    protected ReferenceManager createReferenceManager() {
        return new ReferenceManager();
    }

    /**
     * 计算指定对象的哈希值，并应用额外的哈希函数以减少冲突。
     * 此实现使用与 {@link ConcurrentHashMap} 相同的 Wang/Jenkins 算法。
     * 子类可覆盖此方法以提供替代的哈希实现。
     *
     * @param o 要计算哈希的对象（可为 null）
     * @return 计算得到的哈希码
     */
    protected int getHash(Object o) {
        int hash = (o != null ? o.hashCode() : 0);
        hash += (hash << 15) ^ 0xffffcd7d;
        hash ^= (hash >>> 10);
        hash += (hash << 3);
        hash ^= (hash >>> 6);
        hash += (hash << 2) + (hash << 14);
        hash ^= (hash >>> 16);
        return hash;
    }

    @Override

    public V get(Object key) {
        Reference<K, V> ref = getReference(key, Restructure.WHEN_NECESSARY);
        Entry<K, V> entry = (ref != null ? ref.get() : null);
        return (entry != null ? entry.getValue() : null);
    }

    @Override

    public V getOrDefault(Object key, V defaultValue) {
        Reference<K, V> ref = getReference(key, Restructure.WHEN_NECESSARY);
        Entry<K, V> entry = (ref != null ? ref.get() : null);
        return (entry != null ? entry.getValue() : defaultValue);
    }

    @Override
    public boolean containsKey(Object key) {
        Reference<K, V> ref = getReference(key, Restructure.WHEN_NECESSARY);
        Entry<K, V> entry = (ref != null ? ref.get() : null);
        return (entry != null && ObjectUtils.nullSafeEquals(entry.getKey(), key));
    }

    /**
     * 返回指定 {@code key} 对应的 {@link Entry} 的 {@link Reference} 引用，
     * 如果未找到则返回 {@code null}。
     *
     * @param key         键（可为 null）
     * @param restructure 此调用允许的重构类型
     * @return 引用对象，未找到时返回 {@code null}
     */

    protected final Reference<K, V> getReference(Object key, Restructure restructure) {
        int hash = getHash(key);
        return getSegmentForHash(hash).getReference(key, hash, restructure);
    }

    @Override

    public V put(K key, V value) {
        return put(key, value, true);
    }

    @Override

    public V putIfAbsent(K key, V value) {
        return put(key, value, false);
    }


    private V put(final K key, final V value, final boolean overwriteExisting) {
        return doTask(key, new AbstractTask<V>(TaskOption.RESTRUCTURE_BEFORE, TaskOption.RESIZE) {
            @Override

            protected V execute(Reference<K, V> ref, Entry<K, V> entry, Entries<V> entries) {
                if (entry != null) {
                    V oldValue = entry.getValue();
                    if (overwriteExisting) {
                        entry.setValue(value);
                    }
                    return oldValue;
                }
                entries.add(value);
                return null;
            }
        });
    }

    @Override

    public V remove(Object key) {
        return doTask(key, new AbstractTask<V>(TaskOption.RESTRUCTURE_AFTER, TaskOption.SKIP_IF_EMPTY) {
            @Override

            protected V execute(Reference<K, V> ref, Entry<K, V> entry) {
                if (entry != null) {
                    if (ref != null) {
                        ref.release();
                    }
                    return entry.value;
                }
                return null;
            }
        });
    }

    @Override
    public boolean remove(Object key, final Object value) {
        Boolean result = doTask(key, new AbstractTask<Boolean>(TaskOption.RESTRUCTURE_AFTER, TaskOption.SKIP_IF_EMPTY) {
            @Override
            protected Boolean execute(Reference<K, V> ref, Entry<K, V> entry) {
                if (entry != null && ObjectUtils.nullSafeEquals(entry.getValue(), value)) {
                    if (ref != null) {
                        ref.release();
                    }
                    return true;
                }
                return false;
            }
        });
        return (Boolean.TRUE.equals(result));
    }

    @Override
    public boolean replace(K key, final V oldValue, final V newValue) {
        Boolean result = doTask(key, new AbstractTask<Boolean>(TaskOption.RESTRUCTURE_BEFORE, TaskOption.SKIP_IF_EMPTY) {
            @Override
            protected Boolean execute(Reference<K, V> ref, Entry<K, V> entry) {
                if (entry != null && ObjectUtils.nullSafeEquals(entry.getValue(), oldValue)) {
                    entry.setValue(newValue);
                    return true;
                }
                return false;
            }
        });
        return (Boolean.TRUE.equals(result));
    }

    @Override

    public V replace(K key, final V value) {
        return doTask(key, new AbstractTask<V>(TaskOption.RESTRUCTURE_BEFORE, TaskOption.SKIP_IF_EMPTY) {
            @Override

            protected V execute(Reference<K, V> ref, Entry<K, V> entry) {
                if (entry != null) {
                    V oldValue = entry.getValue();
                    entry.setValue(value);
                    return oldValue;
                }
                return null;
            }
        });
    }

    @Override
    public void clear() {
        for (Segment segment : this.segments) {
            segment.clear();
        }
    }

    /**
     * 移除所有已被 GC 回收、不再被引用的条目。
     * 正常情况下，当 Map 中添加或移除条目时，已被 GC 回收的条目会自动清理。
     * 此方法可用于强制清理，适用于 Map 读取频繁但更新较少的场景。
     */
    public void purgeUnreferencedEntries() {
        for (Segment segment : this.segments) {
            segment.restructureIfNecessary(false);
        }
    }


    @Override
    public int size() {
        int size = 0;
        for (Segment segment : this.segments) {
            size += segment.getCount();
        }
        return size;
    }

    @Override
    public boolean isEmpty() {
        for (Segment segment : this.segments) {
            if (segment.getCount() > 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        Set<Map.Entry<K, V>> entrySet = this.entrySet;
        if (entrySet == null) {
            entrySet = new EntrySet();
            this.entrySet = entrySet;
        }
        return entrySet;
    }


    private <T> T doTask(Object key, AbstractTask<T> task) {
        int hash = getHash(key);
        return getSegmentForHash(hash).doTask(hash, key, task);
    }

    private Segment getSegmentForHash(int hash) {
        return this.segments[(hash >>> (32 - this.shift)) & (this.segments.length - 1)];
    }

    /**
     * 计算移位值，可用于在指定的最大值和最小值之间创建一个 2 的幂次值。
     *
     * @param minimumValue 最小值
     * @param maximumValue 最大值
     * @return 计算得到的移位值（使用 {@code 1 << shift} 获得实际值）
     */
    protected static int calculateShift(int minimumValue, int maximumValue) {
        int shift = 0;
        int value = 1;
        while (value < minimumValue && value < maximumValue) {
            value <<= 1;
            shift++;
        }
        return shift;
    }


    /**
     * 此 Map 支持的引用类型。
     */
    public enum ReferenceType {

        /**
         * 使用 {@link SoftReference 软引用}。
         */
        SOFT,

        /**
         * 使用 {@link WeakReference 弱引用}。
         */
        WEAK
    }


    /**
     * 单个分段（Segment），用于将 Map 拆分为多个段以提高并发性能。
     */
    protected final class Segment extends ReentrantLock {

        private final ReferenceManager referenceManager;

        private final int initialSize;

        /**
         * 引用数组，使用哈希值的低位进行索引。
         * 此属性应与 {@code resizeThreshold} 同时设置。
         */
        private volatile Reference<K, V>[] references;

        /**
         * 此分段中包含的引用总数。包括链式引用和已被 GC 回收但尚未清除的引用。
         */
        private final AtomicInteger count = new AtomicInteger();

        /**
         * 扩容阈值，当 {@code count} 超过此值时引用数组将被扩容。
         */
        private int resizeThreshold;

        public Segment(int initialSize, int resizeThreshold) {
            this.referenceManager = createReferenceManager();
            this.initialSize = initialSize;
            this.references = createReferenceArray(initialSize);
            this.resizeThreshold = resizeThreshold;
        }


        public Reference<K, V> getReference(Object key, int hash, Restructure restructure) {
            if (restructure == Restructure.WHEN_NECESSARY) {
                restructureIfNecessary(false);
            }
            if (this.count.get() == 0) {
                return null;
            }
            // 使用局部副本以防止其他线程同时写入
            Reference<K, V>[] references = this.references;
            int index = getIndex(hash, references);
            Reference<K, V> head = references[index];
            return findInChain(head, key, hash);
        }

        /**
         * 对此分段执行更新操作，更新期间该分段将被锁定。
         *
         * @param hash 键的哈希值
         * @param key  键
         * @param task 更新操作任务
         * @return 操作结果
         */

        public <T> T doTask(final int hash, final Object key, final AbstractTask<T> task) {
            boolean resize = task.hasOption(TaskOption.RESIZE);
            if (task.hasOption(TaskOption.RESTRUCTURE_BEFORE)) {
                restructureIfNecessary(resize);
            }
            if (task.hasOption(TaskOption.SKIP_IF_EMPTY) && this.count.get() == 0) {
                return task.execute(null, null, null);
            }
            lock();
            try {
                final int index = getIndex(hash, this.references);
                final Reference<K, V> head = this.references[index];
                Reference<K, V> ref = findInChain(head, key, hash);
                Entry<K, V> entry = (ref != null ? ref.get() : null);
                Entries<V> entries = value -> {
                    Entry<K, V> newEntry = new Entry<>((K) key, value);
                    Reference<K, V> newReference = Segment.this.referenceManager.createReference(newEntry, hash, head);
                    Segment.this.references[index] = newReference;
                    Segment.this.count.incrementAndGet();
                };
                return task.execute(ref, entry, entries);
            } finally {
                unlock();
                if (task.hasOption(TaskOption.RESTRUCTURE_AFTER)) {
                    restructureIfNecessary(resize);
                }
            }
        }

        /**
         * 清空此分段中的所有条目。
         */
        public void clear() {
            if (this.count.get() == 0) {
                return;
            }
            lock();
            try {
                this.references = createReferenceArray(this.initialSize);
                this.resizeThreshold = (int) (this.references.length * getLoadFactor());
                this.count.set(0);
            } finally {
                unlock();
            }
        }

        /**
         * 当需要时重构底层数据结构。此方法可以扩大引用表的大小，同时清除已
         * 被 GC 回收的引用。
         *
         * @param allowResize 是否允许扩容
         */
        protected final void restructureIfNecessary(boolean allowResize) {
            int currCount = this.count.get();
            boolean needsResize = allowResize && (currCount > 0 && currCount >= this.resizeThreshold);
            Reference<K, V> ref = this.referenceManager.pollForPurge();
            if (ref != null || (needsResize)) {
                restructure(allowResize, ref);
            }
        }

        private void restructure(boolean allowResize, Reference<K, V> ref) {
            boolean needsResize;
            lock();
            try {
                int countAfterRestructure = this.count.get();
                Set<Reference<K, V>> toPurge = Collections.emptySet();
                if (ref != null) {
                    toPurge = new HashSet<>();
                    while (ref != null) {
                        toPurge.add(ref);
                        ref = this.referenceManager.pollForPurge();
                    }
                }
                countAfterRestructure -= toPurge.size();

                // 在锁内重新计算 count，考虑即将被清除的条目
                needsResize = (countAfterRestructure > 0 && countAfterRestructure >= this.resizeThreshold);
                boolean resizing = false;
                int restructureSize = this.references.length;
                if (allowResize && needsResize && restructureSize < MAXIMUM_SEGMENT_SIZE) {
                    restructureSize <<= 1;
                    resizing = true;
                }

                // 创建新表或复用现有表
                Reference<K, V>[] restructured =
                        (resizing ? createReferenceArray(restructureSize) : this.references);

                // 执行重构
                for (int i = 0; i < this.references.length; i++) {
                    ref = this.references[i];
                    if (!resizing) {
                        restructured[i] = null;
                    }
                    while (ref != null) {
                        if (!toPurge.contains(ref)) {
                            Entry<K, V> entry = ref.get();
                            if (entry != null) {
                                int index = getIndex(ref.getHash(), restructured);
                                restructured[index] = this.referenceManager.createReference(
                                        entry, ref.getHash(), restructured[index]);
                            }
                        }
                        ref = ref.getNext();
                    }
                }

                // 替换 volatile 成员
                if (resizing) {
                    this.references = restructured;
                    this.resizeThreshold = (int) (this.references.length * getLoadFactor());
                }
                this.count.set(Math.max(countAfterRestructure, 0));
            } finally {
                unlock();
            }
        }


        private Reference<K, V> findInChain(Reference<K, V> ref, Object key, int hash) {
            Reference<K, V> currRef = ref;
            while (currRef != null) {
                if (currRef.getHash() == hash) {
                    Entry<K, V> entry = currRef.get();
                    if (entry != null) {
                        K entryKey = entry.getKey();
                        if (ObjectUtils.nullSafeEquals(entryKey, key)) {
                            return currRef;
                        }
                    }
                }
                currRef = currRef.getNext();
            }
            return null;
        }

        private Reference<K, V>[] createReferenceArray(int size) {
            return new Reference[size];
        }

        private int getIndex(int hash, Reference<K, V>[] references) {
            return (hash & (references.length - 1));
        }

        /**
         * 返回当前引用数组的大小。
         */
        public final int getSize() {
            return this.references.length;
        }

        /**
         * 返回此分段中的引用总数。
         */
        public final int getCount() {
            return this.count.get();
        }
    }


    /**
     * 对 Map 中 {@link Entry} 条目的引用。实现类通常是对特定 Java 引用实现
     * （如 {@link SoftReference}）的包装。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    protected interface Reference<K, V> {

        /**
         * 返回被引用的条目，如果条目不再可用则返回 {@code null}。
         * @return 被引用的条目，或 {@code null}
         */

        Entry<K, V> get();

        /**
         * 返回此引用的哈希值。
         * @return 哈希值
         */
        int getHash();

        /**
         * 返回链中的下一个引用，如果没有则返回 {@code null}。
         * @return 下一个引用，或 {@code null}
         */

        Reference<K, V> getNext();

        /**
         * 释放此条目，确保它将从 {@code ReferenceManager#pollForPurge()} 中返回。
         */
        void release();
    }


    /**
     * 单个 Map 条目。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    protected static final class Entry<K, V> implements Map.Entry<K, V> {


        /**
         * 键
         */
        private final K key;


        /**
         * 值
         */
        private volatile V value;

        public Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override

        public K getKey() {
            return this.key;
        }

        @Override

        public V getValue() {
            return this.value;
        }

        @Override

        public V setValue(V value) {
            V previous = this.value;
            this.value = value;
            return previous;
        }

        @Override
        public String toString() {
            return (this.key + "=" + this.value);
        }

        @Override
        public final boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Map.Entry)) {
                return false;
            }
            Map.Entry otherEntry = (Map.Entry) other;
            return (ObjectUtils.nullSafeEquals(getKey(), otherEntry.getKey()) &&
                    ObjectUtils.nullSafeEquals(getValue(), otherEntry.getValue()));
        }

        @Override
        public int hashCode() {
            return (ObjectUtils.nullSafeHashCode(this.key) ^ ObjectUtils.nullSafeHashCode(this.value));
        }
    }


    /**
     * 可在 {@link Segment} 上执行的 {@link Segment#doTask 任务}。
     */
    private abstract class AbstractTask<T> {

        private final EnumSet<TaskOption> options;

        public AbstractTask(TaskOption... options) {
            this.options = (options.length == 0 ? EnumSet.noneOf(TaskOption.class) : EnumSet.of(options[0], options));
        }

        public boolean hasOption(TaskOption option) {
            return this.options.contains(option);
        }

        /**
         * 执行任务。
         *
         * @param ref     找到的引用（或 {@code null}）
         * @param entry   找到的条目（或 {@code null}）
         * @param entries 底层条目的访问接口
         * @return 任务执行结果
         * @see #execute(Reference, Entry)
         */

        protected T execute(Reference<K, V> ref, Entry<K, V> entry, Entries<V> entries) {
            return execute(ref, entry);
        }

        /**
         * 便捷方法，适用于无需访问 {@link Entries} 的任务。
         *
         * @param ref   找到的引用（或 {@code null}）
         * @param entry 找到的条目（或 {@code null}）
         * @return 任务执行结果
         * @see #execute(Reference, Entry, Entries)
         */

        protected T execute(Reference<K, V> ref, Entry<K, V> entry) {
            return null;
        }
    }


    /**
     * {@code Task} 支持的各种选项。
     */
    private enum TaskOption {
        /** 执行前重构 */
        RESTRUCTURE_BEFORE,
        /** 执行后重构 */
        RESTRUCTURE_AFTER,
        /** 如果为空则跳过 */
        SKIP_IF_EMPTY,
        /** 允许扩容 */
        RESIZE
    }


    /**
     * 允许任务访问 {@link Segment} 中的条目。
     */
    private interface Entries<V> {

        /**
         * 添加一个指定值的新条目。
         *
         * @param value 要添加的值
         */
        void add(V value);
    }


    /**
     * 内部 EntrySet 视图实现。
     */
    private class EntrySet extends AbstractSet<Map.Entry<K, V>> {

        @Override
        public Iterator<Map.Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        @Override
        public boolean contains(Object o) {
            if (o instanceof Map.Entry<?, ?>) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
                Reference<K, V> ref = ConcurrentReferenceHashMap.this.getReference(entry.getKey(), Restructure.NEVER);
                Entry<K, V> otherEntry = (ref != null ? ref.get() : null);
                if (otherEntry != null) {
                    return ObjectUtils.nullSafeEquals(entry.getValue(), otherEntry.getValue());
                }
            }
            return false;
        }

        @Override
        public boolean remove(Object o) {
            if (o instanceof Map.Entry<?, ?>) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
                return ConcurrentReferenceHashMap.this.remove(entry.getKey(), entry.getValue());
            }
            return false;
        }

        @Override
        public int size() {
            return ConcurrentReferenceHashMap.this.size();
        }

        @Override
        public void clear() {
            ConcurrentReferenceHashMap.this.clear();
        }
    }


    /**
     * 内部 Entry 迭代器实现。
     */
    private class EntryIterator implements Iterator<Map.Entry<K, V>> {

        private int segmentIndex;

        private int referenceIndex;


        private Reference<K, V>[] references;


        private Reference<K, V> reference;


        private Entry<K, V> next;


        private Entry<K, V> last;

        public EntryIterator() {
            moveToNextSegment();
        }

        @Override
        public boolean hasNext() {
            getNextIfNecessary();
            return (this.next != null);
        }

        @Override
        public Entry<K, V> next() {
            getNextIfNecessary();
            if (this.next == null) {
                throw new NoSuchElementException();
            }
            this.last = this.next;
            this.next = null;
            return this.last;
        }

        private void getNextIfNecessary() {
            while (this.next == null) {
                moveToNextReference();
                if (this.reference == null) {
                    return;
                }
                this.next = this.reference.get();
            }
        }

        private void moveToNextReference() {
            if (this.reference != null) {
                this.reference = this.reference.getNext();
            }
            while (this.reference == null && this.references != null) {
                if (this.referenceIndex >= this.references.length) {
                    moveToNextSegment();
                    this.referenceIndex = 0;
                } else {
                    this.reference = this.references[this.referenceIndex];
                    this.referenceIndex++;
                }
            }
        }

        private void moveToNextSegment() {
            this.reference = null;
            this.references = null;
            if (this.segmentIndex < ConcurrentReferenceHashMap.this.segments.length) {
                this.references = ConcurrentReferenceHashMap.this.segments[this.segmentIndex].references;
                this.segmentIndex++;
            }
        }

        @Override
        public void remove() {
            ConcurrentReferenceHashMap.this.remove(this.last.getKey());
            this.last = null;
        }
    }


    /**
     * 可执行的重构类型。
     */
    protected enum Restructure {
        /** 必要时重构 */
        WHEN_NECESSARY,
        /** 永不重构 */
        NEVER
    }


    /**
     * 用于管理 {@link Reference 引用} 的策略类。
     * 如果需要支持其他引用类型，可以重写此类。
     */
    protected class ReferenceManager {

        private final ReferenceQueue<Entry<K, V>> queue = new ReferenceQueue<>();

        /**
         * 工厂方法，用于创建新的 {@link Reference}。
         *
         * @param entry 引用中包含的条目
         * @param hash  哈希值
         * @param next  链中的下一个引用，如果没有则为 {@code null}
         * @return 新的 {@link Reference}
         */
        public Reference<K, V> createReference(Entry<K, V> entry, int hash, Reference<K, V> next) {
            if (ConcurrentReferenceHashMap.this.referenceType == ReferenceType.WEAK) {
                return new WeakEntryReference<>(entry, hash, next, this.queue);
            }
            return new SoftEntryReference<>(entry, hash, next, this.queue);
        }

        /**
         * 返回已被 GC 回收、可从底层结构中清除的引用，如果没有需要清除的引用则返回
         * {@code null}。此方法必须是线程安全的，理想情况下在返回 {@code null} 时不应阻塞。
         * 每个引用应仅被返回一次。
         *
         * @return 需要清除的引用，或 {@code null}
         */

        public Reference<K, V> pollForPurge() {
            return (Reference<K, V>) this.queue.poll();
        }
    }


    /**
     * 针对 {@link SoftReference 软引用} 的内部 {@link Reference} 实现。
     */
    private static final class SoftEntryReference<K, V> extends SoftReference<Entry<K, V>> implements Reference<K, V> {

        private final int hash;


        private final Reference<K, V> nextReference;

        public SoftEntryReference(Entry<K, V> entry, int hash, Reference<K, V> next,
                                  ReferenceQueue<Entry<K, V>> queue) {

            super(entry, queue);
            this.hash = hash;
            this.nextReference = next;
        }

        @Override
        public int getHash() {
            return this.hash;
        }

        @Override

        public Reference<K, V> getNext() {
            return this.nextReference;
        }

        @Override
        public void release() {
            enqueue();
            clear();
        }
    }


    /**
     * 针对 {@link WeakReference 弱引用} 的内部 {@link Reference} 实现。
     */
    private static final class WeakEntryReference<K, V> extends WeakReference<Entry<K, V>> implements Reference<K, V> {

        private final int hash;


        private final Reference<K, V> nextReference;

        public WeakEntryReference(Entry<K, V> entry, int hash, Reference<K, V> next,
                                  ReferenceQueue<Entry<K, V>> queue) {

            super(entry, queue);
            this.hash = hash;
            this.nextReference = next;
        }

        @Override
        public int getHash() {
            return this.hash;
        }

        @Override
        public Reference<K, V> getNext() {
            return this.nextReference;
        }

        @Override
        public void release() {
            enqueue();
            clear();
        }
    }

}