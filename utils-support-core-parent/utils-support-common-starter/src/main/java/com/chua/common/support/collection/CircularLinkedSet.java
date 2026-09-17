package com.chua.common.support.collection;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
* 基于 {@link LinkedHashMap} 的有序环状集合实现，固定容量。
* <p>
* 通过访问顺序（或插入顺序）追踪元素的新旧程度，当元素数量达到容量上限后，
* 新元素加入时的处理方式由 {@link OverflowPolicy} 决定：
* </p>
* <ul>
*   <li>{@link OverflowPolicy#REJECT} — 拒绝录入，新元素被丢弃，集合内容不变</li>
*   <li>{@link OverflowPolicy#EVICT_ELDEST} — 移除最旧的元素后录入新元素</li>
*   <li>{@link OverflowPolicy#EVICT_NEWEST} — 移除最新的元素后录入新元素</li>
* </ul>
* <p>
* 保证集合中无重复元素。
* </p>
*
* <h3>适用场景</h3>
* <ul>
*   <li><strong>最近访问 Key 记录</strong> — 固定容量记录最近访问过的热点 Key，用于本地热点统计</li>
*   <li><strong>去重滑动窗口</strong> — 固定窗口内去重，如最近访问过的用户/设备 ID 集合</li>
*   <li><strong>LRU 风格有限去重缓存</strong> — 容量固定、不允许重复，按策略淘汰，适合轻量缓存</li>
*   <li><strong>频率控制集合</strong> — 只关注“最近是否出现过”，不关心具体频次</li>
* </ul>
*
* <p>
* 线程不安全，多线程环境请自行加锁或使用 {@link Collections#synchronizedSet(Set)} 包装。
* </p>
*
* @param <E> 元素类型
* @author CH
* @since 4.0.0.42
* @version 1.0.0
* @see CircularSet
* @see OverflowPolicy
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
    * 当前溢出策略
    */
    private OverflowPolicy policy;

    /**
    * 是否按访问顺序排序（true）或插入顺序排序（false）。
    * <p>true 时，最近访问的元素排在最前面，peekEldest() 返回最久未被访问的元素。</p>
    */
    private final boolean accessOrder;

    /**
    * 最近一次因容量满而被淘汰的元素
    * <p>仅在 {@link #add(Object)} 真正触发淘汰时设置，其余情况为 null，
    * 便于调用方区分"本次 add 是否淘汰了元素"，避免读到陈旧淘汰值。</p>
    */
    private volatile E lastEvicted;

    /**
    * 使用指定容量创建环状集合，默认按插入顺序排序，默认策略为删除最早。
    *
    * @param capacity 集合容量，必须大于 0
    * @param <E>      元素类型
    * @return 环状集合实例
    * @throws IllegalArgumentException 如果 capacity 小于等于 0
    */
    public static <E> CircularLinkedSet<E> of(int capacity) {
        return new CircularLinkedSet<>(capacity);
    }

    /**
    * 使用指定容量和初始集合创建环状集合。
    *
    * @param capacity 集合容量，必须大于 0
    * @param c        初始集合
    * @param <E>      元素类型
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
    * 使用指定容量和溢出策略创建环状集合。
    *
    * @param capacity 集合容量，必须大于 0
    * @param policy   溢出策略，不允许为 null
    * @param <E>      元素类型
    * @return 环状集合实例
    * @throws IllegalArgumentException 如果 capacity 小于等于 0
    */
    public static <E> CircularLinkedSet<E> of(int capacity, OverflowPolicy policy) {
        return new CircularLinkedSet<>(capacity, false, policy);
    }

    /**
    * 构造方法，按插入顺序排序，默认策略为删除最早。
    *
    * @param capacity 集合容量
    */
    public CircularLinkedSet(int capacity) {
        this(capacity, false, OverflowPolicy.EVICT_ELDEST);
    }

    /**
    * 构造方法。
    *
    * @param capacity    集合容量
    * @param accessOrder 是否按访问顺序排序
    */
    public CircularLinkedSet(int capacity, boolean accessOrder) {
        this(capacity, accessOrder, OverflowPolicy.EVICT_ELDEST);
    }

    /**
    * 构造方法。
    *
    * @param capacity    集合容量
    * @param accessOrder 是否按访问顺序排序
    * @param policy      溢出策略，不允许为 null
    * @throws IllegalArgumentException 如果 capacity 小于等于 0
    */
    public CircularLinkedSet(int capacity, boolean accessOrder, OverflowPolicy policy) {
        super();
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须大于 0");
        }
        if (policy == null) {
            throw new NullPointerException("溢出策略不允许为 null");
        }
        this.capacity = capacity;
        this.accessOrder = accessOrder;
        this.policy = policy;
        this.delegate = new LinkedHashMap<E, Object>(capacity, 0.75f, accessOrder);
    }

    @Override
    /** Capacity */
    public int capacity() {
        return capacity;
    }

    @Override
    /** Policy */
    public OverflowPolicy policy() {
        return policy;
    }

    @Override
    /** 设置Policy */
    public void setPolicy(OverflowPolicy policy) {
        if (policy == null) {
            throw new NullPointerException("溢出策略不允许为 null");
        }
        this.policy = policy;
    }

    @Override
    /** 查看Eldest */
    public E peekEldest() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        Iterator<E> it = delegate.keySet().iterator();
        return it.next();
    }

    @Override
    /** 取出Eldest */
    public E pollEldest() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        Iterator<E> it = delegate.keySet().iterator();
        E eldest = it.next();
        it.remove();
        return eldest;
    }

    @Override
    /** LastEvicted */
    public E lastEvicted() {
        return lastEvicted;
    }

    @Override
    /** 获取大小 */
    public int size() {
        return delegate.size();
    }

    @Override
    /** 是否Empty */
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    /** Contains */
    public boolean contains(Object o) {
        return delegate.containsKey(o);
    }

    @Override
    /** Iterator */
    public Iterator<E> iterator() {
        return delegate.keySet().iterator();
    }

    @Override
    /** ToArray */
    public Object[] toArray() {
        return delegate.keySet().toArray();
    }

    @Override
    /** ToArray */
    public <T> T[] toArray(T[] a) {
        return delegate.keySet().toArray(a);
    }

    @Override
    /** 添加 */
    public boolean add(E e) {
        // 每次 add 前重置淘汰记录，仅本次触发淘汰时更新
        lastEvicted = null;
        // 元素已存在
        if (contains(e)) {
            if (accessOrder) {
                // 访问顺序模式下，刷新元素位置（先移除再插入）
                delegate.remove(e);
            } else {
                return false;
            }
        }
        // 容量已满，按策略处理
        if (size() >= capacity) {
            if (!handleOverflow()) {
                return false;
            }
        }
        delegate.put(e, PRESENT);
        return true;
    }

    @Override
    /** 移除 */
    public boolean remove(Object o) {
        return delegate.remove(o) != null;
    }

    @Override
    /** ContainsAll */
    public boolean containsAll(Collection<?> c) {
        return delegate.keySet().containsAll(c);
    }

    @Override
    /** 添加All */
    public boolean addAll(Collection<? extends E> c) {
        boolean modified = false;
        for (E e : c) {
            modified |= add(e);
        }
        return modified;
    }

    @Override
    /** RetainAll */
    public boolean retainAll(Collection<?> c) {
        boolean modified = delegate.keySet().retainAll(c);
        return modified;
    }

    @Override
    /** 移除All */
    public boolean removeAll(Collection<?> c) {
        boolean modified = false;
        for (Object o : c) {
            modified |= remove(o);
        }
        return modified;
    }

    @Override
    /** Clear */
    public void clear() {
        delegate.clear();
    }

    @Override
    /** 判断相等 */
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
    /** HashCode */
    public int hashCode() {
        return delegate.keySet().hashCode();
    }

    @Override
    /** ToString */
    public String toString() {
        return delegate.keySet().toString();
    }

    /**
    * 处理容量已满时的溢出逻辑。
    *
    * @return 是否腾出了空间（可继续录入）
    */
    private boolean handleOverflow() {
        switch (policy) {
            case REJECT -> {
                // 拒绝录入，记录本次被拒绝的元素不存在（无淘汰）
                return false;
            }
            case EVICT_ELDEST -> {
                // 移除最早元素
                lastEvicted = pollEldest();
                return true;
            }
            case EVICT_NEWEST -> {
                // 移除最新元素
                lastEvicted = pollNewest();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
    * 移除并返回最新的元素。
    *
    * @return 最新元素
    */
    private E pollNewest() {
        Iterator<E> it = delegate.keySet().iterator();
        E newest = null;
        while (it.hasNext()) {
            newest = it.next();
        }
        // 迭代器此时位于末尾，调用 remove 移除最后一个元素
        // 由于是 KeySet 视图迭代器，remove 前需先取到该元素
        if (newest != null) {
            it.remove();
        }
        return newest;
    }
}
