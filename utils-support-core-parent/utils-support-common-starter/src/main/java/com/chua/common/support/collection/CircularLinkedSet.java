package com.chua.common.support.collection;

import java.util.*;

/**
 * 基于 {@link LinkedHashMap} 的有序环状集合实现，固定容量。
 * <p>
 * 通过访问顺序（或插入顺序）追踪元素的新旧程度，
 * 当元素数量达到容量上限后，新元素加入时最旧的元素会被自动移除。
 * 保证集合中无重复元素。
 * </p>
 *
 * <h3>适用场景</h3>
 * <ul>
 *   <li><strong>最近访问 Key 记录</strong> — 固定容量记录最近访问过的热点 Key，用于本地热点统计</li>
 *   <li><strong>去重滑动窗口</strong> — 固定窗口内去重，如最近访问过的用户/设备 ID 集合</li>
 *   <li><strong>LRU 风格有限去重缓存</strong> — 容量固定、不允许重复， oldest 自动淘汰，适合轻量缓存</li>
 *   <li><strong>频率控制集合</strong> — 只关注“最近是否出现过”，不关心具体频次</li>
 * </ul>
 *
 * <p>
 * 线程不安全，多线程环境请自行加锁或使用 {@link Collections#synchronizedSet(Set)} 包装。
 * </p>
 *
 * @param <E> 元素类型
 * @author CH
 * @version 1.0.0
 * @see CircularSet
 */
public class CircularLinkedSet<E> extends AbstractSet<E> implements CircularSet<E> {

    /**
     * 内部存储 Map
     */
    private final LinkedHashMap<E, Object> delegate;

    /**
     * 标记值，用于 {@link LinkedHashMap} 的值
     */
    private static final Object PRESENT = new Object();

    /**
     * 集合固定容量
     */
    private final int capacity;

/**
     * 是否按访问顺序排序（true）或插入顺序排序（false）。
     * <p>true 时，最近访问的元素排在最前面，peekEldest() 返回最久未被访问的元素。</p>
     */
    private final boolean accessOrder;

    /**
     * 是否拒绝溢出（true 时满后拒绝新元素，false 时满后淘汰最旧元素）。
     */
    private final boolean rejectOnFull;

    /**
     * 最近一次被淘汰的元素（因溢出策略移除）。
     */
    private volatile E lastEvicted;

    /**
     * 创建一个具有指定容量和溢出策略的环状集合。
     *
     * <p>当 {@code policy} 为 {@link OverflowPolicy#REJECT} 时，集合满后拒绝新元素；
     * 为 {@link OverflowPolicy#EVICT_ELDEST} 时，集合满后淘汰最旧元素再添加新元素。</p>
     *
     * @param capacity 集合容量
     * @param policy   溢出策略
     * @param <E>      元素类型
     * @return 新创建的环状集合
     */
    public static <E> CircularLinkedSet<E> of(int capacity, OverflowPolicy policy) {
        return new CircularLinkedSet<>(capacity, false, policy == OverflowPolicy.REJECT);
    }

    /**
     * 创建一个具有指定容量的空环状集合（按插入顺序淘汰）。
     *
     * @param capacity 集合容量
     * @param <E>      元素类型
     * @return 新创建的环状集合
     */
    public static <E> CircularLinkedSet<E> of(int capacity) {
        return new CircularLinkedSet<>(capacity);
    }

    /**
     * 使用指定容量和初始集合创建环状集合。
     *
     * @param capacity  集合容量，必须大于 0
     * @param c         初始集合
     * @param <E>       元素类型
     * @return 环状集合实例
     * @throws IllegalArgumentException 如果 capacity 小于等于 0
     */
    public static <E> CircularLinkedSet<E> of(int capacity, Collection<? extends E> c) {
        CircularLinkedSet<E> set = new CircularLinkedSet<>(capacity);
        if (c != null) {
            set.addAll(c);
        }
        return set;
    }

    /**
     * 构造方法，按插入顺序排序。
     *
     * @param capacity 集合容量
     */
    public CircularLinkedSet(int capacity) {
        this(capacity, false);
    }

    /**
     * 构造方法。
     *
     * @param capacity    集合容量
     * @param accessOrder 是否按访问顺序排序
     */
    public CircularLinkedSet(int capacity, boolean accessOrder) {
        this(capacity, accessOrder, false);
    }

    /**
     * 构造方法。
     *
     * @param capacity     集合容量
     * @param accessOrder  是否按访问顺序排序
     * @param rejectOnFull 是否拒绝溢出（true 时满后拒绝新元素）
     */
    public CircularLinkedSet(int capacity, boolean accessOrder, boolean rejectOnFull) {
        super();
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须大于 0");
        }
        this.capacity = capacity;
        this.accessOrder = accessOrder;
        this.rejectOnFull = rejectOnFull;
        this.delegate = new LinkedHashMap<E, Object>(capacity, 0.75f, accessOrder) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<E, Object> eldest) {
                // 拒绝溢出模式下不自动淘汰，由 add() 拦截
                if (rejectOnFull) {
                    return false;
                }
                if (size() > CircularLinkedSet.this.capacity) {
                    CircularLinkedSet.this.lastEvicted = eldest.getKey();
                    return true;
                }
                return false;
            }
        };
    }

    @Override
    public E lastEvicted() {
        return lastEvicted;
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public E peekEldest() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        Iterator<E> it = delegate.keySet().iterator();
        return it.next();
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
    public boolean contains(Object o) {
        return delegate.containsKey(o);
    }

    @Override
    public Iterator<E> iterator() {
        return delegate.keySet().iterator();
    }

    @Override
    public Object[] toArray() {
        return delegate.keySet().toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return delegate.keySet().toArray(a);
    }

    @Override
    public boolean add(E e) {
        // 拒绝溢出模式下，满后直接拒绝
        if (rejectOnFull && size() >= capacity) {
            return false;
        }
        if (contains(e)) {
            if (accessOrder) {
                delegate.remove(e);
            } else {
                return false;
            }
        }
        delegate.put(e, PRESENT);
        return true;
    }

    @Override
    public boolean remove(Object o) {
        return delegate.remove(o) != null;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return delegate.keySet().containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        boolean modified = false;
        for (E e : c) {
            modified |= add(e);
        }
        return modified;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        boolean modified = delegate.keySet().retainAll(c);
        return modified;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean modified = false;
        for (Object o : c) {
            modified |= remove(o);
        }
        return modified;
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Set<?> other)) {
            return false;
        }
        if (other.size() != size()) {
            return false;
        }
        return delegate.keySet().equals(other);
    }

    @Override
    public int hashCode() {
        return delegate.keySet().hashCode();
    }

    @Override
    public String toString() {
        return delegate.keySet().toString();
    }
}
