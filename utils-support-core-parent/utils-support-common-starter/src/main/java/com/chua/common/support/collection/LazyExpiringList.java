package com.chua.common.support.collection;

import com.chua.common.support.serialize.JavaSerializer;
import com.chua.common.support.serialize.Serializer;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 懒加载过期列表：首次访问时通过 loader 加载数据，支持 TTL 自动回收、手动 evict/clear、
 * 堆内（OnHeapDataStore）/堆外（OffHeapDataStore）双后端、maxCapacity 容量保护与生命周期回调。
 *
 * <h3>状态转换</h3>
 * <pre>
 *   UNLOADED ──访问──> LOADING ──成功──> LOADED ──TTL到期/evict/clear──> UNLOADED
 *                          │
 *                        失败
 *                          ↓
 *                     UNLOADED（触发 LOAD_FAILED 事件并抛出 IllegalStateException）
 *
 *   任意状态 ──close()──> CLOSED（后续数据访问抛出 IllegalStateException，double close 安全）
 * </pre>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>{@link #equals(Object)}/{@link #hashCode()}/{@link #toString()} 为恒等语义，
 *       不触发懒加载</li>
 *   <li>TTL 采用读时惰性检查：任一数据访问先执行过期判定，命中即释放底层存储并回到 UNLOADED，
 *       避免后台线程；{@code expiryCheckIntervalMillis} 仅作为检查粒度提示保留</li>
 *   <li>并发首访由 {@code loadLock} 保证只加载一次</li>
 *   <li>offHeap 模式下 add/addAll 抛出 {@link UnsupportedOperationException}（堆外只读语义）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 * @see ListState
 * @see OnHeapDataStore
 * @see OffHeapDataStore
 */
public final class LazyExpiringList<E> extends AbstractList<E> implements AutoCloseable {

    /** 默认 TTL：0 表示不过期 */
    private static final long DEFAULT_TTL_MILLIS = 0L;
    /** 默认过期检查间隔提示值 */
    private static final long DEFAULT_EXPIRY_CHECK_INTERVAL_MILLIS = 1000L;
    /** 默认最大容量 */
    private static final int DEFAULT_MAX_CAPACITY = Integer.MAX_VALUE;

    /** 数据加载器 */
    private final Supplier<List<E>> loader;
    /** TTL 毫秒（0 = 不过期） */
    private final long ttlMillis;
    /** 过期检查间隔毫秒（读时惰性检查的粒度提示） */
    private final long expiryCheckIntervalMillis;
    /** 最大容量 */
    private final int maxCapacity;
    /** 是否使用堆外存储 */
    private final boolean offHeap;
    /** 生命周期监听器 */
    private final Consumer<LifecycleEvent> lifecycleListener;

    /** 加载锁：保证并发首访只加载一次 */
    private final ReentrantLock loadLock = new ReentrantLock();

    /** 当前状态 */
    private ListState state = ListState.UNLOADED;
    /** 底层存储（未加载或已释放时为 null） */
    private DataStore<E> store;
    /** 加载完成时间戳（纳秒），用于 TTL 判定 */
    private long loadedAtNanos;

    /**
    * 私有构造，通过 {@link #builder()} 创建。
    * @param b 方法入参 b
    */
    private LazyExpiringList(Builder<E> b) {
        this.loader = b.loader;
        this.ttlMillis = b.ttlMillis;
        this.expiryCheckIntervalMillis = b.expiryCheckIntervalMillis;
        this.maxCapacity = b.maxCapacity;
        this.offHeap = b.offHeap;
        this.lifecycleListener = b.lifecycleListener;
    }

    // ==================== Builder ====================

    /**
     * 创建构建器。
     *
     * @param <E> 元素类型
     * @return 构建器实例
     */
    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    /**
     * 懒加载列表构建器。
     *
     * @param <E> 元素类型
     */
    public static final class Builder<E> {

        /** 数据加载器（必填） */
        private Supplier<List<E>> loader;
        /** TTL 毫秒 */
        private long ttlMillis = DEFAULT_TTL_MILLIS;
        /** 过期检查间隔毫秒 */
        private long expiryCheckIntervalMillis = DEFAULT_EXPIRY_CHECK_INTERVAL_MILLIS;
        /** 最大容量 */
        private int maxCapacity = DEFAULT_MAX_CAPACITY;
        /** 是否堆外 */
        private boolean offHeap;
        /** 生命周期监听器 */
        private Consumer<LifecycleEvent> lifecycleListener;

        /**
         * 设置数据加载器（必填）。
         *
         * @param loader 加载器
         * @return 当前构建器
         */
        public Builder<E> loader(Supplier<List<E>> loader) {
            this.loader = loader;
            return this;
        }

        /**
         * 设置 TTL 毫秒（0 = 不过期）。
         *
         * @param millis TTL 毫秒
         * @return 当前构建器
         */
        public Builder<E> ttlMillis(long millis) {
            this.ttlMillis = millis;
            return this;
        }

        /**
         * 设置过期检查间隔毫秒。
         *
         * @param millis 检查间隔毫秒
         * @return 当前构建器
         */
        public Builder<E> expiryCheckIntervalMillis(long millis) {
            this.expiryCheckIntervalMillis = millis;
            return this;
        }

        /**
         * 设置最大容量，加载数据超出时自动截断。
         *
         * @param capacity 最大容量
         * @return 当前构建器
         */
        public Builder<E> maxCapacity(int capacity) {
            this.maxCapacity = capacity;
            return this;
        }

        /**
         * 是否使用堆外存储。
         *
         * @param enable true 启用堆外
         * @return 当前构建器
         */
        public Builder<E> offHeap(boolean enable) {
            this.offHeap = enable;
            return this;
        }

        /**
         * 设置生命周期监听器。
         *
         * @param listener 监听器
         * @return 当前构建器
         */
        public Builder<E> lifecycleListener(Consumer<LifecycleEvent> listener) {
            this.lifecycleListener = listener;
            return this;
        }

        /**
         * 构建懒加载过期列表实例。
         *
         * @return 列表实例
         * @throws IllegalArgumentException loader 未设置时抛出
         */
        public LazyExpiringList<E> build() {
            if (loader == null) {
                throw new IllegalArgumentException("loader 必须设置");
            }
            return new LazyExpiringList<>(this);
        }
    }

    // ==================== 生命周期事件 ====================

    /**
     * 生命周期事件：携带事件类型。
     */
    public static final class LifecycleEvent {

        /**
         * 事件类型枚举。
         */
        public enum Type {
            /** 数据加载完成 */
            LOADED,
            /** 手动释放（evict） */
            EVICTED,
            /** 关闭 */
            CLOSED,
            /** 加载失败 */
            LOAD_FAILED
        }

        /** 事件类型 */
        private final Type type;

        /**
            * 私有构造，由内部触发。
            *
            * @param type 事件类型
            */
        private LifecycleEvent(Type type) {
            this.type = type;
        }

        /**
         * 获取事件类型。
         *
         * @return 事件类型
         */
        public Type getType() {
            return type;
        }
    }

    // ==================== 公共 API ====================

    /**
     * 获取当前状态（含 TTL 惰性判定，不触发加载）。
     *
     * @return 当前状态
     */
    public ListState getState() {
        checkExpiry();
        return state;
    }

    /**
     * 是否为堆外存储模式。
     *
     * @return true 表示堆外
     */
    public boolean isOffHeap() {
        return store != null ? store.isOffHeap() : offHeap;
    }

    /**
     * 获取当前堆外占用字节数（非堆外或已释放为 0）。不触发加载。
     *
     * @return 堆外字节数
     */
    public long getOffHeapBytes() {
        return store != null ? store.getOffHeapBytes() : 0L;
    }

    /**
     * 手动释放已加载数据，状态回到 UNLOADED，下次访问重新懒加载。
     */
    public void evict() {
        requireNotClosed();
        checkExpiry();
        releaseIfLoaded(ListState.UNLOADED, LifecycleEvent.Type.EVICTED);
    }

    /**
     * 清空数据：与 evict 等价的释放语义（不触发 EVICTED 事件），状态回到 UNLOADED。
     */
    @Override
    public void clear() {
        requireNotClosed();
        checkExpiry();
        releaseIfLoaded(ListState.UNLOADED, null);
    }

    /**
     * 关闭列表：释放底层存储，状态置为 CLOSED。
     * <p>double close 安全；关闭后数据访问抛出 {@link IllegalStateException}。</p>
     */
    @Override
    public void close() {
        loadLock.lock();
        try {
            if (state == ListState.CLOSED) {
                return;
            }
            releaseStore();
            state = ListState.CLOSED;
            fire(LifecycleEvent.Type.CLOSED);
        } finally {
            loadLock.unlock();
        }
    }

    // ==================== AbstractList 数据访问 ====================

    /**
     * 返回元素个数（触发懒加载）。
     *
     * @return 元素个数
     * @throws IllegalStateException 已关闭或加载失败时抛出
     */
    @Override
    public int size() {
        ensureLoaded();
        return store.size();
    }

    /**
     * 返回指定位置元素（触发懒加载）。
     *
     * @param index 下标
     * @return 元素
     * @throws IllegalStateException 已关闭或加载失败时抛出
     */
    @Override
    public E get(int index) {
        ensureLoaded();
        return store.get(index);
    }

    /**
     * 追加元素（仅堆内模式）。
     *
     * @param element 元素
     * @return 是否追加成功（达到 maxCapacity 时返回 false）
     * @throws UnsupportedOperationException 堆外模式抛出
     * @throws IllegalStateException 已关闭或加载失败时抛出
     */
    @Override
    public boolean add(E element) {
        ensureLoaded();
        if (store.isOffHeap()) {
            throw new UnsupportedOperationException("offHeap 模式不支持 add");
        }
        if (store.size() >= maxCapacity) {
            return false;
        }
        store.append(element);
        return true;
    }

    /**
     * 批量追加（仅堆内模式）；超过 maxCapacity 时自动截断保留前缀。
     *
     * @param c 待追加集合
     * @return 实际追加个数
     * @throws UnsupportedOperationException 堆外模式抛出
     * @throws IllegalStateException 已关闭或加载失败时抛出
     */
    @Override
    public boolean addAll(java.util.Collection<? extends E> c) {
        ensureLoaded();
        if (store.isOffHeap()) {
            throw new UnsupportedOperationException("offHeap 模式不支持 addAll");
        }
        int appended = 0;
        for (E e : c) {
            if (store.size() >= maxCapacity) {
                break;
            }
            store.append(e);
            appended++;
        }
        return appended > 0;
    }

    // ==================== 恒等语义：不触发懒加载 ====================

    /**
     * 恒等比较，不触发懒加载。
     *
     * @param o 比较对象
     * @return 同一实例返回 true
     */
    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    /**
     * 基于身份的哈希，不触发懒加载。
     *
     * @return 身份哈希
     */
    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    /**
     * 字符串表示（含当前状态名），不触发懒加载。
     *
     * @return 形如 LazyExpiringList{state=UNLOADED}
     */
    @Override
    public String toString() {
        int sizeHint;
        if (state == ListState.LOADED && store != null) {
            sizeHint = store.size();
        } else {
            sizeHint = -1;
        }
        return "LazyExpiringList{state=" + state.name()
                + ", offHeap=" + isOffHeap()
                + ", size=" + sizeHint + "}";
    }

    // ==================== 内部实现 ====================

    /**
     * 确保数据已加载：UNLOADED 时执行加载（并发安全）。
     *
     * @throws IllegalStateException 已关闭或加载失败时抛出
     */
    private void ensureLoaded() {
        requireNotClosed();
        checkExpiry();
        if (state == ListState.LOADED) {
            return;
        }
        loadLock.lock();
        try {
            if (state == ListState.LOADED) {
                return;
            }
            state = ListState.LOADING;
            try {
                List<E> data = new ArrayList<>(loader.get());
                if (data.size() > maxCapacity) {
                    data = new ArrayList<>(data.subList(0, maxCapacity));
                }
                DataStore<E> created = createStore();
                for (E e : data) {
                    created.append(e);
                }
                this.store = created;
                this.loadedAtNanos = System.nanoTime();
                state = ListState.LOADED;
                fire(LifecycleEvent.Type.LOADED);
            } catch (RuntimeException e) {
                releaseStore();
                state = ListState.UNLOADED;
                fire(LifecycleEvent.Type.LOAD_FAILED);
                throw new IllegalStateException("懒加载数据失败: " + e.getMessage(), e);
            }
        } finally {
            loadLock.unlock();
        }
    }

    /**
     * TTL 读时惰性判定：LOADED 且超期则释放回 UNLOADED（触发 EVICTED 事件）。
     */
    private void checkExpiry() {
        if (ttlMillis <= 0 || state != ListState.LOADED) {
            return;
        }
        long elapsedMillis = (System.nanoTime() - loadedAtNanos) / 1_000_000L;
        if (elapsedMillis >= ttlMillis) {
            loadLock.lock();
            try {
                if (state != ListState.LOADED) {
                    return;
                }
                releaseStore();
                state = ListState.UNLOADED;
                fire(LifecycleEvent.Type.EVICTED);
            } finally {
                loadLock.unlock();
            }
        }
    }

    /**
     * 若处于 LOADED 则释放存储并迁移状态（可携带事件）。
     *
     * @param nextState 目标状态
     * @param event     触发的事件，null 表示不触发
     */
    private void releaseIfLoaded(ListState nextState, LifecycleEvent.Type event) {
        loadLock.lock();
        try {
            if (state != ListState.LOADED) {
                return;
            }
            releaseStore();
            state = nextState;
            fire(event);
        } finally {
            loadLock.unlock();
        }
    }

    /**
     * 释放底层存储（幂等）。
     */
    private void releaseStore() {
        DataStore<E> s = this.store;
        this.store = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
                // 释放失败不影响主流程
            }
        }
    }

    /**
     * 按配置创建存储后端。
     *
     * @return 存储实例
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private DataStore<E> createStore() {
        if (offHeap) {
            // 堆外序列化要求元素可序列化（OffHeapDataStore/Serializer 上界）；由调用方在运行时保证
            return (DataStore<E>) new OffHeapDataStore(new JavaSerializer());
        }
        return new OnHeapDataStore<>();
    }

    /**
     * 校验未关闭。
     *
     * @throws IllegalStateException 已关闭时抛出
     */
    private void requireNotClosed() {
        if (state == ListState.CLOSED) {
            throw new IllegalStateException("LazyExpiringList 已关闭");
        }
    }

    /**
     * 触发生命周期事件。
     *
     * @param type 事件类型，null 忽略
     */
    private void fire(LifecycleEvent.Type type) {
        if (type == null || lifecycleListener == null) {
            return;
        }
        try {
            lifecycleListener.accept(new LifecycleEvent(type));
        } catch (Exception ignored) {
            // 监听器异常不影响主流程
        }
    }
}
