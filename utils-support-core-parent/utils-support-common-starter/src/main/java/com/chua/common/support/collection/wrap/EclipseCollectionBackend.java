package com.chua.common.support.collection.wrap;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;
import org.eclipse.collections.impl.set.mutable.UnifiedSet;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Eclipse Collections 后端（默认后端）。
 *
 * <p>以内存优先为选型原则（数据为 ThreadMXBean 线程分配字节实测，10 万条目 / 8 条目 1000 个）：</p>
 * <ul>
 *   <li>列表 {@link FastList}：与 ArrayList 同为数组存储，内存持平；</li>
 *   <li>集合 {@link UnifiedSet}：链式桶去 Node 化，较 HashSet 省约 46%（8 条目场景约 71%）；</li>
 *   <li>映射 {@link UnifiedMap}：key/value 内联进单表，较 HashMap 省约 13%（8 条目场景约 54%）；</li>
 *   <li>队列 / 双端队列：Eclipse Collections 13.0.0 无对应实现，如实回退 {@link ArrayDeque}；</li>
 *   <li>有序集合：EC 的 TreeSortedSet / TreeSortedMap 内部即 JDK TreeSet / TreeMap 薄壳
 *       （已核验其字段即 treeSet / treeMap），再包一层只会多耗内存，故直接用 JDK 存储；</li>
 *   <li>插入序映射 / 插入序集合 / 优先队列 / 枚举键映射：EC 13.0.0 无
 *       LinkedHashMap / LinkedHashSet / PriorityQueue / EnumMap 等价物，如实回退
 *       JDK 实现（语义与原生完全一致），插槽保留给能提供更省内存实现的第三方后端。</li>
 * </ul>
 *
 * <p>构造时校验 Eclipse Collections 是否在 classpath：若被下游显式排除，
 * 构造即抛出异常，{@link CollectionBackends} 捕获后回退 {@link JdkCollectionBackend}，
 * 保证集合创建路径永不因缺库而失败。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = {"eclipse", "ec"}, order = 100)
@SpiDefault
public class EclipseCollectionBackend implements CollectionBackend {

    /**
     * 后端名称。
     */
    public static final String NAME = "eclipse";

    /**
     * Eclipse Collections 核心实现类全限定名（用于 classpath 探测）。
     */
    private static final String EC_PRESENT_PROBE = "org.eclipse.collections.impl.map.mutable.UnifiedMap";

    /**
     * 构造：classpath 缺少 Eclipse Collections 时立即失败，交由解析器回退 JDK 后端。
     *
     * <p>直接用 {@code Class.forName} 做初始化探测，不走
     * {@code ClassUtils.isPresent}：后者以类名为唯一键做跨 ClassLoader 缓存，
     * 一次误判会毒化整个 JVM（如某测试线程上下文加载器不可见 EC 时）；
     * 且本类静态引用 EC 类型（FastList/UnifiedMap/UnifiedSet），探测成功本就等价于
     * 类已可加载。</p>
     */
    public EclipseCollectionBackend() {
        if (!isPresent(EC_PRESENT_PROBE)) {
            throw new IllegalStateException(
                    "Eclipse Collections 不在 classpath，无法启用集合后端 " + NAME);
        }
    }

    /**
     * 用调用方与线程上下文两个类加载器直接探测类是否存在，不做任何缓存。
     *
     * @param className 待探测的全限定类名
     * @return 类可加载返回 {@code true}，否则 {@code false}
     */
    static boolean isPresent(String className) {
        ClassLoader own = EclipseCollectionBackend.class.getClassLoader();
        ClassLoader tccl;
        try {
            tccl = Thread.currentThread().getContextClassLoader();
        } catch (Throwable ignored) {
            tccl = null;
        }
        if (isLoadable(className, own, true) || isLoadable(className, tccl, true)) {
            return true;
        }
        return isLoadable(className, own, false) || isLoadable(className, tccl, false);
    }

    /**
     * 单个类加载器探测。
     *
     * @param className  类名
     * @param loader     类加载器，可为 {@code null}
     * @param initialize 是否触发类初始化
     * @return 可加载返回 {@code true}
     */
    private static boolean isLoadable(String className, ClassLoader loader, boolean initialize) {
        try {
            if (loader != null) {
                Class.forName(className, initialize, loader);
            } else {
                Class.forName(className);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public <E> List<E> newList(int initialCapacity) {
        return initialCapacity > 0 ? new FastList<>(initialCapacity) : new FastList<>();
    }

    @Override
    public <E> Set<E> newSet(int initialCapacity) {
        return initialCapacity > 0 ? new UnifiedSet<>(initialCapacity) : new UnifiedSet<>();
    }

    @Override
    public <K, V> Map<K, V> newMap(int initialCapacity) {
        return initialCapacity > 0 ? new UnifiedMap<>(initialCapacity) : new UnifiedMap<>();
    }

    @Override
    public <E> Queue<E> newQueue(int initialCapacity) {
        return newArrayDeque(initialCapacity);
    }

    @Override
    public <E> Deque<E> newDeque(int initialCapacity) {
        return newArrayDeque(initialCapacity);
    }

    @Override
    public <E> SortedSet<E> newSortedSet() {
        return new TreeSet<>();
    }

    @Override
    public <K, V> SortedMap<K, V> newSortedMap() {
        return new TreeMap<>();
    }

    @Override
    public <K, V> Map<K, V> newLinkedMap(int initialCapacity) {
        return initialCapacity > 0 ? new LinkedHashMap<>(initialCapacity) : new LinkedHashMap<>();
    }

    @Override
    public <E> Set<E> newLinkedSet(int initialCapacity) {
        return initialCapacity > 0 ? new LinkedHashSet<>(initialCapacity) : new LinkedHashSet<>();
    }

    @Override
    public <E> Queue<E> newPriorityQueue(int initialCapacity, Comparator<? super E> comparator) {
        if (initialCapacity > 0) {
            return comparator != null
                    ? new PriorityQueue<>(initialCapacity, comparator)
                    : new PriorityQueue<>(initialCapacity);
        }
        return comparator != null ? new PriorityQueue<>(comparator) : new PriorityQueue<>();
    }

    @Override
    public <K extends Enum<K>, V> Map<K, V> newEnumMap(Class<K> keyType) {
        return new EnumMap<>(keyType);
    }

    /**
     * 创建 ArrayDeque：EC 无队列实现，两后端统一回退 JDK。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <E>             元素类型
     * @return 双端队列存储
     */
    private static <E> ArrayDeque<E> newArrayDeque(int initialCapacity) {
        return initialCapacity > 0 ? new ArrayDeque<>(initialCapacity) : new ArrayDeque<>();
    }
}
