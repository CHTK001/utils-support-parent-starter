package com.chua.common.support.collection.wrap;

import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
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
 * JDK 集合后端（兜底后端）。
 *
 * <p>当 Eclipse Collections 不在 classpath（例如下游依赖显式排除）时，由
 * {@link CollectionBackends} 自动回退到本后端，保证分装体系在任何环境都可用。
 * 存储实现即经典 JDK 集合：{@link ArrayList} / {@link HashSet} / {@link HashMap}，
 * 队列与双端队列同 Eclipse 后端使用 {@link ArrayDeque}，有序集合使用
 * {@link TreeSet} / {@link TreeMap}，插入序集合/映射、优先队列与枚举键映射
 * 使用 {@link LinkedHashSet} / {@link LinkedHashMap} / {@link PriorityQueue} /
 * {@link EnumMap}。</p>
 *
 * <p>本后端不做内存优化（仅为兼容兜底），需要省内存时请确保 Eclipse Collections
 * 在 classpath 并使用默认后端。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see EclipseCollectionBackend
 */
@Spi(value = "jdk", order = -100)
public class JdkCollectionBackend implements CollectionBackend {

    /**
     * 后端名称。
     */
    public static final String NAME = "jdk";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public <E> List<E> newList(int initialCapacity) {
        return initialCapacity > 0 ? new ArrayList<>(initialCapacity) : new ArrayList<>();
    }

    @Override
    public <E> Set<E> newSet(int initialCapacity) {
        return initialCapacity > 0 ? new HashSet<>(initialCapacity) : new HashSet<>();
    }

    @Override
    public <K, V> Map<K, V> newMap(int initialCapacity) {
        return initialCapacity > 0 ? new HashMap<>(initialCapacity) : new HashMap<>();
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
     * 创建 ArrayDeque：两后端对队列统一使用 JDK 实现。
     *
     * @param initialCapacity 初始容量，{@code <= 0} 表示默认值
     * @param <E>             元素类型
     * @return 双端队列存储
     */
    private static <E> ArrayDeque<E> newArrayDeque(int initialCapacity) {
        return initialCapacity > 0 ? new ArrayDeque<>(initialCapacity) : new ArrayDeque<>();
    }
}
