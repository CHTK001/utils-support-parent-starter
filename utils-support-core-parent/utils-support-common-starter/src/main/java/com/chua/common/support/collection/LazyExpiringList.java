package com.chua.common.support.collection;

import com.chua.common.support.serialize.JavaSerializer;
import com.chua.common.support.serialize.Serializer;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * 懒加载过期集合 —— 线程安全的 {@link List} 实现，支持懒加载、TTL 过期自动回收、
 * 释放后退回初始状态、以及可选的堆外内存存储。
 *
 * <h3>核心特性</h3>
 * <ol>
 *   <li><strong>懒加载</strong>：构造时不加载数据，首次访问时通过 loader 加载</li>
 *   <li><strong>过期自动回收</strong>：TTL 到期后自动清空数据、释放内存，回到"未加载"状态</li>
 *   <li><strong>释放后退回初始状态</strong>：过期或手动释放后，下次访问重新懒加载</li>
 *   <li><strong>堆内/堆外可选</strong>：通过 offHeap(true) 启用堆外存储，
 *       使用 {@link Serializer} SPI 可切换序列化策略（java/json），
 *       调用 close() 时 Arena.close() 确定性释放，不等 GC</li>
 * </ol>
 *
 * <h3>状态转换</h3>
 * <pre>
 *   UNLOADED ──→ LOADING ──→ LOADED ──→ UNLOADED (过期回收/手动释放)
 *      ↑            │          │
 *      │         (加载失败)   ↓
 *      └────────────┘      CLOSED (手动关闭，不可逆)
 * </pre>
 *
 * <h3>复用项目基础设施</h3>
 * <ul>
 *   <li>{@link ThreadUtils#newScheduleWithFixedDelay} — TTL 过期检查定时器</li>
 *   <li>{@link Serializer} + {@link ServiceProvider} — 统一序列化 SPI</li>
 *   <li>{@link JavaSerializer} — 默认序列化实现</li>
 * </ul>
 *
 * @param <E> 元素类型，必须实现 {@link Serializable}
 * @author CH
 * @since 4.0.0.42
 * @version 2.0.0
 * @see ListState
 * @see DataStore
 * @see OnHeapDataStore
 * @see OffHeapDataStore
 * @see Serializer
 */
@Slf4j
public class LazyExpiringList<E extends Serializable> implements List<E>, AutoCloseable {

    /** 数据加载器，首次访问时调用 */
    private final Supplier<List<E>> loader;

    /** TTL 过期时间（毫秒），0 表示永不过期 */
    private final long ttlMillis;

    /** 是否使用堆外内存存储 */
    private final boolean offHeap;

    /** 序列化器，堆外模式下用于对象与字节的互转 */
    private final Serializer<E> serializer;

    /** 过期检查间隔（毫秒） */
    private final long expiryCheckIntervalMillis;

    /** 最大容量，0 表示不限制 */
    private final int maxCapacity;

    /** 加载超时时间（毫秒），0 表示无限等待 */
    private final long loadTimeoutMillis;

    /** 生命周期事件监听器 */
    private final LifecycleListener<E> lifecycleListener;

    /** 当前生命周期状态，volatile 保证可见性 */
    private volatile ListState state = ListState.UNLOADED;

    /** 数据存储抽象（堆内或堆外），volatile 保证可见性 */
    private volatile DataStore<E> dataStore;

    /** 最后访问时间戳，用于 TTL 过期判断 */
    private volatile long lastAccessTime;

    /** TTL 过期检查定时任务 */
    private final ScheduledFuture<?> expiryTask;

    /** 加载互斥锁，保证只有一个线程执行加载 */
    private final ReentrantLock loadLock = new ReentrantLock();

    /** 加载完成信号，用于让等待线程阻塞/释放 */
    private volatile CountDownLatch loadLatch;

    /**
     * 私有构造，通过 {@link Builder} 创建。
     */
    private LazyExpiringList(Builder<E> builder) {
        this.loader = Objects.requireNonNull(builder.loader, "loader 不能为 null");
        this.ttlMillis = builder.ttlMillis;
        this.offHeap = builder.offHeap;
        this.serializer = resolveSerializer(builder);
        this.maxCapacity = builder.maxCapacity;
        this.loadTimeoutMillis = builder.loadTimeoutMillis;
        this.lifecycleListener = builder.lifecycleListener;

        if (maxCapacity < 0) {
            throw new IllegalArgumentException("maxCapacity 不能为负数: " + maxCapacity);
        }

        this.expiryCheckIntervalMillis = builder.expiryCheckIntervalMillis > 0
                ? builder.expiryCheckIntervalMillis
                : Math.max(ttlMillis / 3, 1000);

        if (ttlMillis > 0) {
            this.expiryTask = ThreadUtils.newScheduleWithFixedDelay(
                    this::checkExpiry,
                    expiryCheckIntervalMillis,
                    expiryCheckIntervalMillis,
                    TimeUnit.MILLISECONDS
            );
        } else {
            this.expiryTask = null;
        }
    }

    /**
     * 解析序列化器：优先使用 Builder 指定的实例，其次通过 SPI 按名称加载，最后使用默认 JavaSerializer。
     */
    @SuppressWarnings("unchecked")
    private Serializer<E> resolveSerializer(Builder<E> builder) {
        if (builder.serializer != null) {
            return builder.serializer;
        }
        if (builder.serializerType != null) {
            try {
                ServiceProvider<Serializer> provider = ServiceProvider.of(Serializer.class);
                Serializer<E> found = (Serializer<E>) provider.getExtension(builder.serializerType);
                if (found == null) {
                    throw new IllegalArgumentException("未找到名称为 '" + builder.serializerType + "' 的 Serializer SPI 实现");
                }
                return found;
            } catch (Exception e) {
                throw new IllegalArgumentException("通过 SPI 获取序列化器失败: " + builder.serializerType, e);
            }
        }
        return new JavaSerializer<>();
    }

    /**
     * 创建 Builder 实例。
     *
     * @param <E> 元素类型
     * @return 新的 Builder
     */
    public static <E extends Serializable> Builder<E> builder() {
        return new Builder<>();
    }

    /**
     * LazyExpiringList 构建器。
     *
     * <p>必填项：{@link #loader}。其余均为可选，提供合理默认值。</p>
     */
    public static class Builder<E extends Serializable> {
        /** 数据加载器（必填） */
        private Supplier<List<E>> loader;
        /** TTL 过期时间（毫秒），0 = 永不过期 */
        private long ttlMillis;
        /** 是否使用堆外内存 */
        private boolean offHeap;
        /** 序列化器实例（优先级高于 serializerType） */
        private Serializer<E> serializer;
        /** 序列化器 SPI 名称（如 "java"、"json"） */
        private String serializerType;
        /** 元素类型（泛型擦除时辅助 SPI 加载） */
        private Class<E> elementClass;
        /** 过期检查间隔（毫秒），0 = 自动计算 ttlMillis/3 */
        private long expiryCheckIntervalMillis;
        /** 最大容量，0 = 不限制 */
        private int maxCapacity;
        /** 加载超时（毫秒），0 = 无限等待 */
        private long loadTimeoutMillis;
        /** 生命周期事件监听器 */
        private LifecycleListener<E> lifecycleListener;

        private Builder() {}

        /**
         * 设置数据加载器（必填）。
         *
         * @param loader 首次访问时调用的数据加载函数
         * @return this
         */
        public Builder<E> loader(Supplier<List<E>> loader) { this.loader = loader; return this; }

        /**
         * 设置 TTL 过期时间。
         *
         * @param ttlMillis 过期时间（毫秒），0 表示永不过期
         * @return this
         * @throws IllegalArgumentException 如果 ttlMillis 为负数
         */
        public Builder<E> ttlMillis(long ttlMillis) {
            if (ttlMillis < 0) throw new IllegalArgumentException("ttlMillis 不能为负数: " + ttlMillis);
            this.ttlMillis = ttlMillis; return this;
        }

        /**
         * 设置是否使用堆外内存。
         *
         * @param offHeap true 启用堆外存储
         * @return this
         */
        public Builder<E> offHeap(boolean offHeap) { this.offHeap = offHeap; return this; }

        /**
         * 设置序列化器实例（优先级高于 serializerType）。
         *
         * @param serializer 序列化器
         * @return this
         */
        public Builder<E> serializer(Serializer<E> serializer) { this.serializer = serializer; return this; }

        /**
         * 设置序列化器 SPI 名称（如 "java"、"json"）。
         *
         * @param type SPI 名称
         * @return this
         */
        public Builder<E> serializerType(String type) { this.serializerType = type; return this; }

        /**
         * 设置元素类型（泛型擦除时辅助 SPI 加载）。
         *
         * @param elementClass 元素 Class
         * @return this
         */
        public Builder<E> elementClass(Class<E> elementClass) { this.elementClass = elementClass; return this; }

        /**
         * 设置过期检查间隔。
         *
         * @param intervalMillis 检查间隔（毫秒），0 = 自动计算
         * @return this
         */
        public Builder<E> expiryCheckIntervalMillis(long intervalMillis) { this.expiryCheckIntervalMillis = intervalMillis; return this; }

        /**
         * 设置最大容量。
         *
         * @param maxCapacity 最大元素数量，0 = 不限制
         * @return this
         */
        public Builder<E> maxCapacity(int maxCapacity) { this.maxCapacity = maxCapacity; return this; }

        /**
         * 设置加载超时时间。
         *
         * @param timeoutMillis 超时（毫秒），0 = 无限等待
         * @return this
         * @throws IllegalArgumentException 如果 timeoutMillis 为负数
         */
        public Builder<E> loadTimeoutMillis(long timeoutMillis) {
            if (timeoutMillis < 0) throw new IllegalArgumentException("loadTimeoutMillis 不能为负数: " + timeoutMillis);
            this.loadTimeoutMillis = timeoutMillis; return this;
        }

        /**
         * 设置生命周期事件监听器。
         *
         * @param listener 监听器
         * @return this
         */
        public Builder<E> lifecycleListener(LifecycleListener<E> listener) { this.lifecycleListener = listener; return this; }

        /**
         * 构建 LazyExpiringList 实例。
         *
         * @return 新的 LazyExpiringList
         */
        public LazyExpiringList<E> build() { return new LazyExpiringList<>(this); }
    }

    // ==================== 生命周期监听器 ====================

    /**
     * 生命周期事件监听器。
     *
     * @param <E> 元素类型
     */
    @FunctionalInterface
    public interface LifecycleListener<E extends Serializable> {
        /**
         * 处理生命周期事件。
         *
         * @param event 生命周期事件
         */
        void onEvent(Event<E> event);
    }

    /**
     * 生命周期事件。
     */
    public static class Event<E extends Serializable> {
        /** 事件类型 */
        private final Type type;
        /** 事件来源 */
        private final LazyExpiringList<E> source;
        /** 事件时间戳 */
        private final long timestamp;
        /** 事件详情（如加载元素数量、异常对象等） */
        private final Object detail;

        Event(Type type, LazyExpiringList<E> source, Object detail) {
            this.type = type;
            this.source = source;
            this.timestamp = System.currentTimeMillis();
            this.detail = detail;
        }

        /** 获取事件类型 */
        public Type getType() { return type; }
        /** 获取事件来源 */
        public LazyExpiringList<E> getSource() { return source; }
        /** 获取事件时间戳 */
        public long getTimestamp() { return timestamp; }
        /** 获取事件详情 */
        public Object getDetail() { return detail; }

        @Override
        public String toString() { return "Event{type=" + type + ", detail=" + detail + '}'; }

        /**
         * 生命周期事件类型。
         */
        public enum Type {
            /** 数据加载完成 */
            LOADED,
            /** 数据加载失败 */
            LOAD_FAILED,
            /** TTL 过期自动回收 */
            EXPIRED,
            /** 手动释放（evict） */
            EVICTED,
            /** 手动关闭（close） */
            CLOSED
        }
    }

    // ==================== 懒加载核心逻辑 ====================

    /**
     * 确保数据已加载。若未加载则触发懒加载，若正在加载则阻塞等待。
     *
     * @throws IllegalStateException 如果已关闭或加载失败
     */
    private void ensureLoaded() {
        ListState s = state;
        if (s == ListState.LOADED) { touchAccess(); return; }
        if (s == ListState.CLOSED) { throw new IllegalStateException("LazyExpiringList 已关闭，不可访问"); }
        if (s == ListState.LOADING) {
            awaitLoading();
            s = state;
            if (s == ListState.CLOSED) throw new IllegalStateException("LazyExpiringList 已关闭，不可访问");
            if (s == ListState.LOADED) touchAccess();
            return;
        }
        if (s == ListState.UNLOADED) {
            loadLock.lock();
            try {
                s = state;
                if (s == ListState.UNLOADED) {
                    loadLatch = new CountDownLatch(1);
                    try {
                        load();
                    } finally {
                        // CRITICAL: 无论加载成功或失败，都必须释放等待线程
                        loadLatch.countDown();
                    }
                }
            } finally {
                loadLock.unlock();
            }
            s = state;
            if (s == ListState.CLOSED) throw new IllegalStateException("LazyExpiringList 已关闭，不可访问");
            if (s == ListState.LOADED) touchAccess();
        }
    }

    /**
     * 等待正在进行的加载完成。
     *
     * @throws IllegalStateException 如果等待超时或被中断
     */
    private void awaitLoading() {
        CountDownLatch latch = this.loadLatch;
        if (latch == null) {
            int spins = 0;
            while (state == ListState.LOADING && this.loadLatch == null && spins < 1000) {
                Thread.yield();
                spins++;
            }
            latch = this.loadLatch;
            if (latch == null) throw new IllegalStateException("加载信号初始化超时");
        }
        try {
            if (loadTimeoutMillis > 0) {
                boolean completed = latch.await(loadTimeoutMillis, TimeUnit.MILLISECONDS);
                if (!completed) throw new IllegalStateException("等待数据加载超时（" + loadTimeoutMillis + "ms）");
            } else {
                latch.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待数据加载被中断", e);
        }
    }

    /**
     * 执行数据加载，创建 DataStore 并转换状态。
     *
     * @throws IllegalStateException 如果加载失败
     */
    private void load() {
        state = ListState.LOADING;
        try {
            List<E> data = loader.get();
            if (data == null) data = Collections.emptyList();
            if (maxCapacity > 0 && data.size() > maxCapacity) {
                log.warn("加载数据量 {} 超过最大容量 {}，已截断", data.size(), maxCapacity);
                data = new ArrayList<>(data.subList(0, maxCapacity));
            }

            DataStore<E> store;
            if (offHeap) {
                OffHeapDataStore<E> offHeapStore = new OffHeapDataStore<>(serializer);
                offHeapStore.appendAll(data);
                store = offHeapStore;
            } else {
                store = new OnHeapDataStore<>(data);
            }
            this.dataStore = store;

            state = ListState.LOADED;
            lastAccessTime = System.currentTimeMillis();
            fireEvent(Event.Type.LOADED, data.size());
            log.debug("懒加载完成，加载 {} 个元素，堆外模式: {}", data.size(), offHeap);
        } catch (Exception e) {
            state = ListState.UNLOADED;
            fireEvent(Event.Type.LOAD_FAILED, e);
            log.error("懒加载数据失败", e);
            throw new IllegalStateException("懒加载数据失败", e);
        }
    }

    /**
     * 更新最后访问时间戳。
     */
    private void touchAccess() { lastAccessTime = System.currentTimeMillis(); }

    // ==================== 过期回收 ====================

    /**
     * 检查是否 TTL 过期，若过期则触发自动回收。
     */
    private void checkExpiry() {
        try {
            if (state == ListState.LOADED && ttlMillis > 0) {
                long elapsed = System.currentTimeMillis() - lastAccessTime;
                if (elapsed > ttlMillis) {
                    log.debug("TTL 过期触发自动回收，已过 {}ms（TTL={}ms）", elapsed, ttlMillis);
                    evict();
                }
            }
        } catch (Exception e) {
            log.error("过期检查任务异常", e);
        }
    }

    /**
     * 手动释放数据，回到 UNLOADED 状态。下次访问将重新懒加载。
     */
    public void evict() {
        loadLock.lock();
        try {
            if (state == ListState.LOADED) {
                int size = dataStore != null ? dataStore.size() : 0;
                releaseResources();
                state = ListState.UNLOADED;
                fireEvent(Event.Type.EVICTED, size);
                log.debug("数据已释放，回到 UNLOADED 状态，释放 {} 个元素", size);
            }
        } finally {
            loadLock.unlock();
        }
    }

    /**
     * 释放 DataStore 资源。
     */
    private void releaseResources() {
        DataStore<E> store = this.dataStore;
        if (store != null) {
            try { store.close(); } catch (Exception e) { log.warn("关闭 DataStore 异常", e); }
            this.dataStore = null;
        }
    }

    /**
     * 触发生命周期事件。
     */
    private void fireEvent(Event.Type type, Object detail) {
        LifecycleListener<E> listener = this.lifecycleListener;
        if (listener != null) {
            try { listener.onEvent(new Event<>(type, this, detail)); } catch (Exception e) { log.warn("生命周期监听器异常: type={}", type, e); }
        }
    }

    // ==================== 状态查询 ====================

    /** 获取当前生命周期状态 */
    public ListState getState() { return state; }
    /** 判断是否已加载 */
    public boolean isLoaded() { return state == ListState.LOADED; }
    /** 获取最后访问时间戳 */
    public long getLastAccessTime() { return lastAccessTime; }
    /** 获取堆外内存占用字节数 */
    public long getOffHeapBytes() { DataStore<E> store = dataStore; return store != null ? store.getOffHeapBytes() : 0; }
    /** 获取 TTL 过期时间（毫秒） */
    public long getTtlMillis() { return ttlMillis; }
    /** 判断是否使用堆外内存 */
    public boolean isOffHeap() { return offHeap; }
    /** 获取最大容量 */
    public int getMaxCapacity() { return maxCapacity; }
    /** 获取底层 DataStore 实例 */
    public DataStore<E> getDataStore() { return dataStore; }

    // ==================== List 接口实现 ====================

    /** {@inheritDoc} — 触发懒加载后返回元素数量 */
    @Override public int size() { ensureLoaded(); return dataStore.size(); }
    /** {@inheritDoc} — 触发懒加载后判断是否为空 */
    @Override public boolean isEmpty() { ensureLoaded(); return dataStore.isEmpty(); }

    @Override
    public boolean contains(Object o) {
        ensureLoaded();
        for (int i = 0, sz = dataStore.size(); i < sz; i++) {
            if (Objects.equals(dataStore.get(i), o)) return true;
        }
        return false;
    }

    /** {@inheritDoc} — 返回快照迭代器，不反映后续修改 */
    @Override
    public Iterator<E> iterator() { ensureLoaded(); return new DataStoreIterator(); }

    @Override
    public Object[] toArray() {
        ensureLoaded();
        Object[] arr = new Object[dataStore.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = dataStore.get(i);
        return arr;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        ensureLoaded();
        int size = dataStore.size();
        T[] arr = a.length >= size ? a : (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), size);
        for (int i = 0; i < size; i++) arr[i] = (T) dataStore.get(i);
        if (arr.length > size) arr[size] = null;
        return arr;
    }

    /**
     * {@inheritDoc}
     *
     * <p>堆外模式不支持 add 操作（抛出 {@link UnsupportedOperationException}）。
     * 如果超过 maxCapacity 限制，返回 false。</p>
     */
    @Override
    public boolean add(E e) {
        ensureLoaded();
        if (dataStore.isOffHeap()) throw new UnsupportedOperationException("堆外内存模式不支持 add 操作");
        if (maxCapacity > 0 && dataStore.size() >= maxCapacity) {
            log.warn("add 操作超过最大容量 {}，已拒绝", maxCapacity);
            return false;
        }
        dataStore.append(e);
        return true;
    }

    /** {@inheritDoc} — 不支持 remove 操作 */
    @Override
    public boolean remove(Object o) {
        ensureLoaded();
        throw new UnsupportedOperationException("LazyExpiringList 不支持 remove 操作");
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        ensureLoaded();
        for (Object o : c) { if (!contains(o)) return false; }
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>堆外模式不支持 addAll 操作。超过 maxCapacity 时部分截断。</p>
     */
    @Override
    public boolean addAll(Collection<? extends E> c) {
        ensureLoaded();
        if (dataStore.isOffHeap()) throw new UnsupportedOperationException("堆外内存模式不支持 addAll 操作");
        if (maxCapacity > 0) {
            int remaining = maxCapacity - dataStore.size();
            if (remaining <= 0) { log.warn("addAll 超过最大容量 {}，已拒绝", maxCapacity); return false; }
            int toAdd = Math.min(c.size(), remaining);
            if (toAdd < c.size()) log.warn("addAll 部分截断：请求 {}，允许 {}", c.size(), toAdd);
            int count = 0;
            for (E e : c) { if (count >= toAdd) break; dataStore.append(e); count++; }
            return count > 0;
        }
        boolean modified = false;
        for (E e : c) { dataStore.append(e); modified = true; }
        return modified;
    }

    /** {@inheritDoc} — 不支持 */
    @Override public boolean addAll(int index, Collection<? extends E> c) { throw new UnsupportedOperationException(); }
    /** {@inheritDoc} — 不支持 */
    @Override public boolean removeAll(Collection<?> c) { throw new UnsupportedOperationException(); }
    /** {@inheritDoc} — 不支持 */
    @Override public boolean retainAll(Collection<?> c) { throw new UnsupportedOperationException(); }

    /**
     * 清空数据并回到 UNLOADED 状态。
     *
     * @throws IllegalStateException 如果已关闭
     */
    @Override
    public void clear() {
        loadLock.lock();
        try {
            if (state == ListState.CLOSED) throw new IllegalStateException("LazyExpiringList 已关闭");
            releaseResources();
            state = ListState.UNLOADED;
        } finally { loadLock.unlock(); }
    }

    /** {@inheritDoc} — 触发懒加载后获取元素 */
    @Override public E get(int index) { ensureLoaded(); return dataStore.get(index); }
    /** {@inheritDoc} — 不支持 */
    @Override public E set(int index, E element) { throw new UnsupportedOperationException(); }
    /** {@inheritDoc} — 不支持 */
    @Override public void add(int index, E element) { throw new UnsupportedOperationException(); }
    /** {@inheritDoc} — 不支持 */
    @Override public E remove(int index) { throw new UnsupportedOperationException(); }

    @Override
    public int indexOf(Object o) {
        ensureLoaded();
        for (int i = 0, sz = dataStore.size(); i < sz; i++) { if (Objects.equals(dataStore.get(i), o)) return i; }
        return -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        ensureLoaded();
        for (int i = dataStore.size() - 1; i >= 0; i--) { if (Objects.equals(dataStore.get(i), o)) return i; }
        return -1;
    }

    /** {@inheritDoc} — 返回快照列表迭代器 */
    @Override public ListIterator<E> listIterator() { ensureLoaded(); return new DataStoreListIterator(0); }
    /** {@inheritDoc} — 返回快照列表迭代器 */
    @Override public ListIterator<E> listIterator(int index) { ensureLoaded(); return new DataStoreListIterator(index); }

    @Override
    public List<E> subList(int fromIndex, int toIndex) {
        ensureLoaded();
        int size = dataStore.size();
        if (fromIndex < 0 || toIndex > size || fromIndex > toIndex)
            throw new IndexOutOfBoundsException("fromIndex=" + fromIndex + ", toIndex=" + toIndex + ", size=" + size);
        List<E> result = new ArrayList<>(toIndex - fromIndex);
        for (int i = fromIndex; i < toIndex; i++) result.add(dataStore.get(i));
        return result;
    }

    /** {@inheritDoc} — 不支持 */
    @Override public void replaceAll(UnaryOperator<E> operator) { throw new UnsupportedOperationException(); }
    /** {@inheritDoc} — 不支持 */
    @Override public void sort(java.util.Comparator<? super E> c) { throw new UnsupportedOperationException(); }
    /** {@inheritDoc} — 返回快照 Spliterator */
    @Override public Spliterator<E> spliterator() { ensureLoaded(); return new DataStoreSpliterator(); }
    /** {@inheritDoc} — 不支持 */
    @Override public boolean removeIf(Predicate<? super E> filter) { throw new UnsupportedOperationException(); }

    @Override
    public Stream<E> stream() {
        ensureLoaded();
        List<E> snapshot = new ArrayList<>(dataStore.size());
        for (int i = 0, sz = dataStore.size(); i < sz; i++) snapshot.add(dataStore.get(i));
        return snapshot.stream();
    }

    @Override
    public Stream<E> parallelStream() {
        ensureLoaded();
        List<E> snapshot = new ArrayList<>(dataStore.size());
        for (int i = 0, sz = dataStore.size(); i < sz; i++) snapshot.add(dataStore.get(i));
        return snapshot.parallelStream();
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        ensureLoaded();
        for (int i = 0, sz = dataStore.size(); i < sz; i++) action.accept(dataStore.get(i));
    }

    // ==================== 内部迭代器 ====================

    /** 快照迭代器，遍历创建时的数据，不反映后续修改 */
    private class DataStoreIterator implements Iterator<E> {
        private int cursor = 0;
        private final int size = dataStore.size();
        @Override public boolean hasNext() { return cursor < size; }
        @Override public E next() { if (cursor >= size) throw new java.util.NoSuchElementException(); return dataStore.get(cursor++); }
    }

    /** 快照列表迭代器，支持双向遍历 */
    private class DataStoreListIterator implements ListIterator<E> {
        private int cursor;
        private final int size;
        DataStoreListIterator(int index) { this.size = dataStore.size(); if (index < 0 || index > size) throw new IndexOutOfBoundsException(); this.cursor = index; }
        @Override public boolean hasNext() { return cursor < size; }
        @Override public E next() { if (cursor >= size) throw new java.util.NoSuchElementException(); return dataStore.get(cursor++); }
        @Override public boolean hasPrevious() { return cursor > 0; }
        @Override public E previous() { if (cursor <= 0) throw new java.util.NoSuchElementException(); return dataStore.get(--cursor); }
        @Override public int nextIndex() { return cursor; }
        @Override public int previousIndex() { return cursor - 1; }
        @Override public void set(E e) { throw new UnsupportedOperationException(); }
        @Override public void add(E e) { throw new UnsupportedOperationException(); }
        @Override public void remove() { throw new UnsupportedOperationException(); }
    }

    /** 快照 Spliterator */
    private class DataStoreSpliterator implements Spliterator<E> {
        private int cursor = 0;
        private final int size = dataStore.size();
        @Override public boolean tryAdvance(Consumer<? super E> action) { if (cursor < size) { action.accept(dataStore.get(cursor++)); return true; } return false; }
        @Override public Spliterator<E> trySplit() { return null; }
        @Override public long estimateSize() { return size - cursor; }
        @Override public int characteristics() { return Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED; }
    }

    // ==================== AutoCloseable ====================

    /**
     * 关闭集合，释放所有资源。
     *
     * <p>关闭后状态变为 {@link ListState#CLOSED}，不可逆。
     * 堆外模式下将调用 {@link java.lang.foreign.Arena#close()} 确定性释放 native 内存。
     * 同时取消 TTL 过期检查定时任务。</p>
     */
    @Override
    public void close() {
        loadLock.lock();
        try {
            if (state != ListState.CLOSED) {
                releaseResources();
                if (expiryTask != null) expiryTask.cancel(false);
                state = ListState.CLOSED;
                fireEvent(Event.Type.CLOSED, null);
                log.debug("LazyExpiringList 已关闭");
            }
        } finally { loadLock.unlock(); }
    }

    // ==================== Object 方法 ====================

    /**
     * 返回字符串表示。LOADED 状态显示元素（超过 10 个截断），其他状态显示状态信息。
     *
     * <p>不触发懒加载。</p>
     */
    @Override
    public String toString() {
        ListState s = state;
        if (s == ListState.LOADED) {
            DataStore<E> ds = dataStore;
            if (ds != null && ds.size() > 10) {
                StringBuilder sb = new StringBuilder("LazyExpiringList[0..9]=[");
                for (int i = 0; i < 10; i++) { if (i > 0) sb.append(", "); sb.append(ds.get(i)); }
                sb.append("]...(size=").append(ds.size()).append(")");
                return sb.toString();
            }
            if (ds != null) {
                StringBuilder sb = new StringBuilder("LazyExpiringList[");
                for (int i = 0, sz = ds.size(); i < sz; i++) { if (i > 0) sb.append(", "); sb.append(ds.get(i)); }
                sb.append("]");
                return sb.toString();
            }
            return "LazyExpiringList[]";
        }
        return "LazyExpiringList{state=" + s + ", ttl=" + ttlMillis + "ms, offHeap=" + offHeap + ", maxCapacity=" + maxCapacity + '}';
    }

    /**
     * 返回身份哈希码，不触发懒加载。
     */
    @Override public int hashCode() { return System.identityHashCode(this); }

    /**
     * 身份比较，不触发懒加载。
     */
    @Override public boolean equals(Object obj) { return this == obj; }
}
