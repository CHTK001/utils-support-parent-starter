package com.chua.common.support.collection.wrap;

import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 集合分装工厂：统一创建「已分装」的集合对象。
 *
 * <h3>背景</h3>
 * <p>目标是减少内存占用。本工厂创建的每个集合都是一个薄分装对象
 * （每个约 16~20 字节开销），其内部存储由 {@link CollectionBackend} SPI 决定：
 * 默认使用 Eclipse Collections（{@code UnifiedSet} 比 {@code HashSet} 省约 46%、
 * {@code UnifiedMap} 比 {@code HashMap} 省约 13%，小集合场景分别可达 71% / 54%），
 * classpath 缺失或系统属性指定时自动切换为 JDK 实现。</p>
 *
 * <h3>后端切换</h3>
 * <ol>
 *   <li>系统属性：{@code -Dutils.support.collection.backend=jdk}；</li>
 *   <li>代码切换：{@link CollectionBackends#use(String)}；</li>
 *   <li>第三方后端：实现 {@link CollectionBackend} 并注册到
 *       {@code META-INF/services/com.chua.common.support.collection.wrap.CollectionBackend}。</li>
 * </ol>
 *
 * <h3>类型与例外</h3>
 * <p>所有分装对象均实现对应 {@code java.util} 接口（List / Set / Map / Queue /
 * Deque / SortedSet / SortedMap），既有代码零改动即可接收，序列化、
 * {@code equals}/{@code hashCode}/{@code toString} 语义与底层存储一致。</p>
 * <p>以下类型<b>不提供分装</b>（没有更低内存的后端可用，属文档化例外）：</p>
 * <ul>
 *   <li>并发集合（{@code ConcurrentHashMap} / {@code CopyOnWriteArrayList} 等）；</li>
 *   <li>导航集合（{@code TreeSet} 导航视图 / {@code TreeMap} floor、ceiling 等）；
 *       纯排序场景请用 {@link #sortedSet()} / {@link #sortedMap()}；</li>
 *   <li>{@code java.util.Collections} 的不可变/同步包装（本就无冗余存储）。</li>
 * </ul>
 * <p>插入序集合/映射（{@link #linkedSet()} / {@link #linkedMap()}）、优先队列
 * （{@link #priorityQueue(Comparator)}）与枚举键映射（{@link #enumMap(Class)}）
 * <b>提供分装</b>：两个内置后端均以 JDK 对应实现作为存储（Eclipse Collections
 * 13.0.0 无等价物，属如实回退），既有语义（插入顺序 / 堆序 / 枚举序）完全不变。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see CollectionBackend
 * @see CollectionBackends
 */
public final class Wraps {

    /**
     * 内部工具类，禁止实例化。
     */
    private Wraps() {
    }

    // ================================ 后端 ==================================

    /**
     * 当前集合后端。
     *
     * @return 集合后端
     */
    public static CollectionBackend backend() {
        return CollectionBackends.get();
    }

    /**
     * 当前后端名称。
     *
     * @return 后端名称，如 {@code eclipse} / {@code jdk}
     */
    public static String backendName() {
        return CollectionBackends.name();
    }

    // ================================ 列表 ==================================

    /**
     * 创建空列表（默认容量）。
     *
     * @param <E> 元素类型
     * @return 分装列表
     */
    public static <E> List<E> list() {
        return new WrappedList<>(CollectionBackends.get().newList(0));
    }

    /**
     * 创建指定初始容量的空列表。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <E>             元素类型
     * @return 分装列表
     */
    public static <E> List<E> list(int initialCapacity) {
        return new WrappedList<>(CollectionBackends.get().newList(initialCapacity));
    }

    /**
     * 创建包含指定集合全部元素的列表（语义等同 {@code new ArrayList<>(c)}）。
     *
     * @param c 初始元素
     * @param <E> 元素类型
     * @return 分装列表
     */
    public static <E> List<E> list(Collection<? extends E> c) {
        WrappedList<E> wrapped = new WrappedList<>(CollectionBackends.get().newList(c.size()));
        wrapped.addAll(c);
        return wrapped;
    }

    // ================================ 集合 ==================================

    /**
     * 创建空集合（默认容量）。
     *
     * @param <E> 元素类型
     * @return 分装集合
     */
    public static <E> Set<E> set() {
        return new WrappedSet<>(CollectionBackends.get().newSet(0));
    }

    /**
     * 创建指定初始容量的空集合。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <E>             元素类型
     * @return 分装集合
     */
    public static <E> Set<E> set(int initialCapacity) {
        return new WrappedSet<>(CollectionBackends.get().newSet(initialCapacity));
    }

    /**
     * 创建包含指定集合元素的集合（语义等同 {@code new HashSet<>(c)}，重复元素收敛）。
     *
     * @param c 初始元素
     * @param <E> 元素类型
     * @return 分装集合
     */
    public static <E> Set<E> set(Collection<? extends E> c) {
        WrappedSet<E> wrapped = new WrappedSet<>(CollectionBackends.get().newSet(c.size()));
        wrapped.addAll(c);
        return wrapped;
    }

    // ============================== 插入序集合 ==============================

    /**
     * 创建空的插入序集合（迭代顺序为插入顺序，语义等同 {@code new LinkedHashSet<>()}）。
     *
     * @param <E> 元素类型
     * @return 分装插入序集合
     */
    public static <E> Set<E> linkedSet() {
        return new WrappedSet<>(CollectionBackends.get().newLinkedSet(0));
    }

    /**
     * 创建指定初始容量的空插入序集合。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <E>             元素类型
     * @return 分装插入序集合
     */
    public static <E> Set<E> linkedSet(int initialCapacity) {
        return new WrappedSet<>(CollectionBackends.get().newLinkedSet(initialCapacity));
    }

    /**
     * 创建包含指定集合元素的插入序集合（语义等同 {@code new LinkedHashSet<>(c)}）。
     *
     * @param c   初始元素
     * @param <E> 元素类型
     * @return 分装插入序集合
     */
    public static <E> Set<E> linkedSet(Collection<? extends E> c) {
        WrappedSet<E> wrapped = new WrappedSet<>(CollectionBackends.get().newLinkedSet(c.size()));
        wrapped.addAll(c);
        return wrapped;
    }

    // ================================ 映射 ==================================

    /**
     * 创建空映射（默认容量）。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 分装映射
     */
    public static <K, V> Map<K, V> map() {
        return new WrappedMap<>(CollectionBackends.get().newMap(0));
    }

    /**
     * 创建指定初始容量的空映射。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <K>             键类型
     * @param <V>             值类型
     * @return 分装映射
     */
    public static <K, V> Map<K, V> map(int initialCapacity) {
        return new WrappedMap<>(CollectionBackends.get().newMap(initialCapacity));
    }

    /**
     * 创建包含指定映射条目的映射（语义等同 {@code new HashMap<>(m)}）。
     *
     * @param m 初始条目
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 分装映射
     */
    public static <K, V> Map<K, V> map(Map<? extends K, ? extends V> m) {
        WrappedMap<K, V> wrapped = new WrappedMap<>(CollectionBackends.get().newMap(m.size()));
        wrapped.putAll(m);
        return wrapped;
    }

    // ============================== 插入序映射 ==============================

    /**
     * 创建空的插入序映射（迭代顺序为插入顺序，语义等同 {@code new LinkedHashMap<>()}）。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 分装插入序映射
     */
    public static <K, V> Map<K, V> linkedMap() {
        return new WrappedMap<>(CollectionBackends.get().newLinkedMap(0));
    }

    /**
     * 创建指定初始容量的空插入序映射。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <K>             键类型
     * @param <V>             值类型
     * @return 分装插入序映射
     */
    public static <K, V> Map<K, V> linkedMap(int initialCapacity) {
        return new WrappedMap<>(CollectionBackends.get().newLinkedMap(initialCapacity));
    }

    /**
     * 创建包含指定映射条目的插入序映射（语义等同 {@code new LinkedHashMap<>(m)}）。
     *
     * @param m   初始条目（按其迭代顺序放入）
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 分装插入序映射
     */
    public static <K, V> Map<K, V> linkedMap(Map<? extends K, ? extends V> m) {
        WrappedMap<K, V> wrapped = new WrappedMap<>(CollectionBackends.get().newLinkedMap(m.size()));
        wrapped.putAll(m);
        return wrapped;
    }

    // ============================== 队列/双端 ===============================

    /**
     * 创建空队列。
     *
     * @param <E> 元素类型
     * @return 分装队列
     */
    public static <E> Queue<E> queue() {
        return new WrappedQueue<>(CollectionBackends.get().newQueue(0));
    }

    /**
     * 创建指定初始容量的空队列。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <E>             元素类型
     * @return 分装队列
     */
    public static <E> Queue<E> queue(int initialCapacity) {
        return new WrappedQueue<>(CollectionBackends.get().newQueue(initialCapacity));
    }

    /**
     * 创建包含指定集合全部元素的队列（语义等同 {@code new ArrayDeque<>(c)}）。
     *
     * @param c 初始元素
     * @param <E> 元素类型
     * @return 分装队列
     */
    public static <E> Queue<E> queue(Collection<? extends E> c) {
        WrappedQueue<E> wrapped = new WrappedQueue<>(CollectionBackends.get().newQueue(c.size()));
        wrapped.addAll(c);
        return wrapped;
    }

    /**
     * 创建空双端队列。
     *
     * @param <E> 元素类型
     * @return 分装双端队列
     */
    public static <E> Deque<E> deque() {
        return new WrappedDeque<>(CollectionBackends.get().newDeque(0));
    }

    /**
     * 创建指定初始容量的空双端队列。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <E>             元素类型
     * @return 分装双端队列
     */
    public static <E> Deque<E> deque(int initialCapacity) {
        return new WrappedDeque<>(CollectionBackends.get().newDeque(initialCapacity));
    }

    /**
     * 创建包含指定集合全部元素的双端队列（语义等同 {@code new ArrayDeque<>(c)}）。
     *
     * @param c 初始元素
     * @param <E> 元素类型
     * @return 分装双端队列
     */
    public static <E> Deque<E> deque(Collection<? extends E> c) {
        WrappedDeque<E> wrapped = new WrappedDeque<>(CollectionBackends.get().newDeque(c.size()));
        wrapped.addAll(c);
        return wrapped;
    }

    // ============================== 有序集合 ================================

    /**
     * 创建空的自然序有序集合（需要导航操作时请改用 {@code new TreeSet<>()}，
     * 属文档化例外）。
     *
     * @param <E> 元素类型
     * @return 分装有序集合
     */
    public static <E> SortedSet<E> sortedSet() {
        return new WrappedSortedSet<>(CollectionBackends.get().newSortedSet());
    }

    /**
     * 创建空的比较器排序有序集合。
     *
     * @param comparator 比较器，{@code null} 表示自然序
     * @param <E>        元素类型
     * @return 分装有序集合
     */
    public static <E> SortedSet<E> sortedSet(java.util.Comparator<? super E> comparator) {
        SortedSet<E> storage = CollectionBackends.get().newSortedSet();
        if (comparator != null) {
            storage = new java.util.TreeSet<>(comparator);
        }
        return new WrappedSortedSet<>(storage);
    }

    /**
     * 创建包含指定集合元素的自然序有序集合（语义等同 {@code new TreeSet<>(c)}）。
     *
     * @param c 初始元素
     * @param <E> 元素类型
     * @return 分装有序集合
     */
    public static <E> SortedSet<E> sortedSet(Collection<? extends E> c) {
        SortedSet<E> storage = CollectionBackends.get().newSortedSet();
        WrappedSortedSet<E> wrapped = new WrappedSortedSet<>(storage);
        wrapped.addAll(c);
        return wrapped;
    }

    /**
     * 创建空的自然序有序映射（需要导航操作时请改用 {@code new TreeMap<>()}，
     * 属文档化例外）。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 分装有序映射
     */
    public static <K, V> SortedMap<K, V> sortedMap() {
        return new WrappedSortedMap<>(CollectionBackends.get().newSortedMap());
    }

    /**
     * 创建空的比较器排序有序映射。
     *
     * @param comparator 比较器，{@code null} 表示自然序
     * @param <K>        键类型
     * @param <V>        值类型
     * @return 分装有序映射
     */
    public static <K, V> SortedMap<K, V> sortedMap(java.util.Comparator<? super K> comparator) {
        SortedMap<K, V> storage = CollectionBackends.get().newSortedMap();
        if (comparator != null) {
            storage = new java.util.TreeMap<>(comparator);
        }
        return new WrappedSortedMap<>(storage);
    }

    /**
     * 创建包含指定映射条目的自然序有序映射（语义等同 {@code new TreeMap<>(m)}）。
     *
     * @param m   初始条目
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 分装有序映射
     */
    public static <K, V> SortedMap<K, V> sortedMap(Map<? extends K, ? extends V> m) {
        WrappedSortedMap<K, V> wrapped =
                new WrappedSortedMap<>(CollectionBackends.get().newSortedMap());
        wrapped.putAll(m);
        return wrapped;
    }

    // ============================== 优先队列 ================================

    /**
     * 创建空的自然序优先队列（语义等同 {@code new PriorityQueue<>()}）。
     *
     * @param <E> 元素类型（须可比较）
     * @return 分装优先队列
     */
    public static <E> Queue<E> priorityQueue() {
        return new WrappedQueue<>(CollectionBackends.get().newPriorityQueue(0, null));
    }

    /**
     * 创建指定初始容量的自然序优先队列。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <E>             元素类型（须可比较）
     * @return 分装优先队列
     */
    public static <E> Queue<E> priorityQueue(int initialCapacity) {
        return new WrappedQueue<>(CollectionBackends.get().newPriorityQueue(initialCapacity, null));
    }

    /**
     * 创建空的比较器优先队列（语义等同 {@code new PriorityQueue<>(comparator)}）。
     *
     * @param comparator 比较器，{@code null} 表示自然序
     * @param <E>        元素类型
     * @return 分装优先队列
     */
    public static <E> Queue<E> priorityQueue(Comparator<? super E> comparator) {
        return new WrappedQueue<>(CollectionBackends.get().newPriorityQueue(0, comparator));
    }

    /**
     * 创建指定初始容量的比较器优先队列
     * （语义等同 {@code new PriorityQueue<>(initialCapacity, comparator)}）。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param comparator      比较器，{@code null} 表示自然序
     * @param <E>             元素类型
     * @return 分装优先队列
     */
    public static <E> Queue<E> priorityQueue(int initialCapacity, Comparator<? super E> comparator) {
        return new WrappedQueue<>(CollectionBackends.get().newPriorityQueue(initialCapacity, comparator));
    }

    // ============================== 枚举键映射 ==============================

    /**
     * 创建枚举键映射（语义等同 {@code new EnumMap<>(keyType)}，按键枚举序号紧凑存储，
     * 迭代顺序为枚举声明序）。
     *
     * @param keyType 枚举键类型
     * @param <K>     枚举键类型
     * @param <V>     值类型
     * @return 分装枚举键映射
     */
    public static <K extends Enum<K>, V> Map<K, V> enumMap(Class<K> keyType) {
        return new WrappedMap<>(CollectionBackends.get().newEnumMap(keyType));
    }

    // ============================ 分装既有对象 ==============================

    /**
     * 分装既有列表（已是分装对象时原样返回）。
     *
     * @param list 既有列表
     * @param <E>  元素类型
     * @return 分装列表
     */
    public static <E> List<E> wrap(List<E> list) {
        return list instanceof WrappedList ? list : new WrappedList<>(list);
    }

    /**
     * 分装既有集合（已是分装对象时原样返回）。
     *
     * @param set 既有集合
     * @param <E> 元素类型
     * @return 分装集合
     */
    public static <E> Set<E> wrap(Set<E> set) {
        return set instanceof WrappedSet ? set : new WrappedSet<>(set);
    }

    /**
     * 分装既有映射（已是分装对象时原样返回）。
     *
     * @param map 既有映射
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 分装映射
     */
    public static <K, V> Map<K, V> wrap(Map<K, V> map) {
        return map instanceof WrappedMap ? map : new WrappedMap<>(map);
    }

    /**
     * 分装既有有序集合（已是分装对象时原样返回）。
     *
     * @param sortedSet 既有有序集合
     * @param <E>       元素类型
     * @return 分装有序集合
     */
    public static <E> SortedSet<E> wrap(SortedSet<E> sortedSet) {
        return sortedSet instanceof WrappedSortedSet ? sortedSet : new WrappedSortedSet<>(sortedSet);
    }

    /**
     * 分装既有有序映射（已是分装对象时原样返回）。
     *
     * @param sortedMap 既有有序映射
     * @param <K>       键类型
     * @param <V>       值类型
     * @return 分装有序映射
     */
    public static <K, V> SortedMap<K, V> wrap(SortedMap<K, V> sortedMap) {
        return sortedMap instanceof WrappedSortedMap ? sortedMap : new WrappedSortedMap<>(sortedMap);
    }

    /**
     * 解包取出底层存储；若对象不是分装对象则原样返回。
     *
     * <p>用于互操作场景：部分第三方库对 {@code ArrayList} / {@code HashMap} 等
     * 具体类型做特化处理，此时传入底层存储即可。</p>
     *
     * @param o 分装对象或普通对象
     * @return 底层存储（非分装对象时即入参本身）
     */
    public static Object unwrap(Object o) {
        if (o instanceof WrappedList) {
            return ((WrappedList<?>) o).delegate();
        }
        if (o instanceof WrappedSet) {
            return ((WrappedSet<?>) o).delegate();
        }
        if (o instanceof WrappedMap) {
            return ((WrappedMap<?, ?>) o).delegate();
        }
        if (o instanceof WrappedSortedSet) {
            return ((WrappedSortedSet<?>) o).delegate();
        }
        if (o instanceof WrappedSortedMap) {
            return ((WrappedSortedMap<?, ?>) o).delegate();
        }
        if (o instanceof WrappedQueue) {
            return ((WrappedQueue<?>) o).delegate();
        }
        if (o instanceof WrappedDeque) {
            return ((WrappedDeque<?>) o).delegate();
        }
        return o;
    }
}
