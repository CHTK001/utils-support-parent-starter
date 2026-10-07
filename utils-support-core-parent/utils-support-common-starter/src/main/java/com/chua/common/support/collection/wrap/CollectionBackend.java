package com.chua.common.support.collection.wrap;

import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;

/**
 * 集合后端 SPI（服务提供者接口）。
 *
 * <p>本接口是 {@link Wraps} 分装体系的可切换底层：每个后端负责提供
 * 「列表 / 集合 / 映射 / 队列 / 双端队列 / 有序集合 / 插入序映射 / 插入序集合 /
 * 优先队列 / 枚举键映射」的存储实现，
 * 由 {@link CollectionBackends} 在运行时通过 SPI 选择一个后端（默认 eclipse），
 * 再由 {@link Wraps} 工厂将存储实例分装为统一的包装对象对外返回。</p>
 *
 * <p>选择流程（见 {@link CollectionBackends}）：</p>
 * <ol>
 *   <li>系统属性 {@value CollectionBackends#PROPERTY} 指定名称时按名称精确匹配；</li>
 *   <li>否则默认使用 {@link EclipseCollectionBackend}（Eclipse Collections）；</li>
 *   <li>Eclipse Collections 不在 classpath 时自动回退 {@link JdkCollectionBackend}。</li>
 * </ol>
 *
 * <p>通过 {@code META-INF/services/} 注册第三方后端即可参与上述选择，
 * 实现不改一行分装代码即可切换集合实现。</p>
 *
 * <h3>实现约束</h3>
 * <ul>
 *   <li>{@link #newList(int)} 返回的存储必须实现 {@link java.util.RandomAccess}
 *       （包装类 {@link WrappedList} 据此声明 RandomAccess，两个内置后端均满足）；</li>
 *   <li>所有返回的存储必须是可序列化的（包装类实现了 Serializable）；</li>
 *   <li>{@code initialCapacity <= 0} 表示使用实现的默认容量；</li>
 *   <li>队列 / 双端队列 / 有序集合 / 插入序映射 / 插入序集合 / 优先队列 /
 *       枚举键映射：Eclipse Collections 无更低内存的等价实现
 *       （其 TreeSortedSet/TreeSortedMap 内部即 JDK TreeSet/TreeMap 薄壳，
 *       13.0.0 亦无 LinkedHashMap/LinkedHashSet/PriorityQueue/EnumMap 等价物），
 *       两个内置后端对这些类型均返回 JDK 存储，属如实回退而非缺陷，
 *       插槽保留给能提供更省内存实现的第三方后端。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 * @see Wraps
 * @see CollectionBackends
 */
public interface CollectionBackend {

    /**
     * 后端名称（小写），用于 SPI 定位与日志标识。
     *
     * @return 后端名称，如 {@code eclipse} / {@code jdk}
     */
    String name();

    /**
     * 创建列表存储。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <E>             元素类型
     * @return 实现了 {@link java.util.RandomAccess} 的列表存储
     */
    <E> List<E> newList(int initialCapacity);

    /**
     * 创建集合存储。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <E>             元素类型
     * @return 集合存储
     */
    <E> Set<E> newSet(int initialCapacity);

    /**
     * 创建映射存储。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <K>             键类型
     * @param <V>             值类型
     * @return 映射存储
     */
    <K, V> Map<K, V> newMap(int initialCapacity);

    /**
     * 创建队列存储。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <E>             元素类型
     * @return 队列存储
     */
    <E> Queue<E> newQueue(int initialCapacity);

    /**
     * 创建双端队列存储。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <E>             元素类型
     * @return 双端队列存储
     */
    <E> Deque<E> newDeque(int initialCapacity);

    /**
     * 创建有序集合存储。
     *
     * @param <E> 元素类型
     * @return 有序集合存储
     */
    <E> SortedSet<E> newSortedSet();

    /**
     * 创建有序映射存储。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 有序映射存储
     */
    <K, V> SortedMap<K, V> newSortedMap();

    /**
     * 创建插入序映射存储（迭代顺序为插入顺序，语义等同 {@link java.util.LinkedHashMap}）。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <K>             键类型
     * @param <V>             值类型
     * @return 插入序映射存储
     */
    <K, V> Map<K, V> newLinkedMap(int initialCapacity);

    /**
     * 创建插入序集合存储（迭代顺序为插入顺序，语义等同 {@link java.util.LinkedHashSet}）。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <E>             元素类型
     * @return 插入序集合存储
     */
    <E> Set<E> newLinkedSet(int initialCapacity);

    /**
     * 创建优先队列存储（堆顶为比较器最小元素，语义等同 {@link java.util.PriorityQueue}）。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param comparator      比较器，{@code null} 表示自然序（元素须可比较）
     * @param <E>             元素类型
     * @return 优先队列存储
     */
    <E> Queue<E> newPriorityQueue(int initialCapacity, Comparator<? super E> comparator);

    /**
     * 创建枚举键映射存储（语义等同 {@link java.util.EnumMap}，按键枚举序号紧凑存储）。
     *
     * @param keyType 枚举键类型
     * @param <K>     枚举键类型
     * @param <V>     值类型
     * @return 枚举键映射存储
     */
    <K extends Enum<K>, V> Map<K, V> newEnumMap(Class<K> keyType);
}
