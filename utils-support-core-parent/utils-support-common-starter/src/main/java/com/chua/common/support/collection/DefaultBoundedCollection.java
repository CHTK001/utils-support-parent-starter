package com.chua.common.support.collection;

import java.util.AbstractCollection;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 默认有界集合实现，基于 {@link ArrayDeque}，固定容量。
 *
 * <p>内部使用双端队列维护元素的加入顺序（FIFO），当元素数量达到容量上限后，
 * 继续添加新元素的行为由 {@link OverflowPolicy} 决定：</p>
 * <ul>
 *   <li>{@link OverflowPolicy#REJECT} — 丢弃新元素，返回 false</li>
 *   <li>{@link OverflowPolicy#EVICT_ELDEST} — 移除队首（最早）元素，再录入新元素</li>
 *   <li>{@link OverflowPolicy#EVICT_NEWEST} — 移除队尾（最新）元素，再录入新元素</li>
 * </ul>
 *
 * <p>允许重复元素；线程不安全，多线程环境请自行加锁或使用
 * {@link java.util.Collections#synchronizedCollection(Collection)} 包装。</p>
 *
 * @param <E> 元素类型
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 * @see BoundedCollection
 * @see OverflowPolicy
 */
public class DefaultBoundedCollection<E> extends AbstractCollection<E> implements BoundedCollection<E> {

    /**
     * 内部存储双端队列
     */
    private final Deque<E> delegate;

    /**
     * 集合固定容量
     */
    private final int capacity;

    /**
     * 当前溢出策略
     */
    private OverflowPolicy policy;

    /**
     * 使用指定容量创建有界集合，默认策略为删除最早（FIFO 淘汰）。
     *
     * @param capacity 集合容量，必须大于 0
     * @param <E>      元素类型
     * @return 有界集合实例
     * @throws IllegalArgumentException 如果 capacity 小于等于 0
     */
    public static <E> BoundedCollection<E> of(int capacity) {
        return new DefaultBoundedCollection<>(capacity, OverflowPolicy.EVICT_ELDEST);
    }

    /**
     * 使用指定容量与初始集合创建有界集合。
     *
     * @param capacity 集合容量，必须大于 0
     * @param c        初始集合，可为 null
     * @param <E>      元素类型
     * @return 有界集合实例
     * @throws IllegalArgumentException 如果 capacity 小于等于 0
     */
    public static <E> BoundedCollection<E> of(int capacity, Collection<? extends E> c) {
        DefaultBoundedCollection<E> result = new DefaultBoundedCollection<>(capacity, OverflowPolicy.EVICT_ELDEST);
        if (c != null) {
            result.addAll(c);
        }
        return result;
    }

    /**
     * 使用指定容量创建有界集合。
     *
     * @param capacity 集合容量，必须大于 0
     */
    public DefaultBoundedCollection(int capacity) {
        this(capacity, OverflowPolicy.EVICT_ELDEST);
    }

    /**
     * 使用指定容量和溢出策略创建有界集合。
     *
     * @param capacity 集合容量，必须大于 0
     * @param policy   溢出策略，不允许为 null
     * @throws IllegalArgumentException 如果 capacity 小于等于 0
     * @throws NullPointerException     如果 policy 为 null
     */
    public DefaultBoundedCollection(int capacity, OverflowPolicy policy) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须大于 0");
        }
        if (policy == null) {
            throw new NullPointerException("溢出策略不允许为 null");
        }
        this.capacity = capacity;
        this.policy = policy;
        this.delegate = new ArrayDeque<>(capacity);
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
        if (delegate.isEmpty()) {
            throw new NoSuchElementException("有界集合为空");
        }
        return delegate.peekFirst();
    }

    @Override
    /** 取出Eldest */
    public E pollEldest() {
        if (delegate.isEmpty()) {
            throw new NoSuchElementException("有界集合为空");
        }
        return delegate.pollFirst();
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
        return delegate.contains(o);
    }

    @Override
    /** Iterator */
    public Iterator<E> iterator() {
        return delegate.iterator();
    }

    @Override
    /** 添加 */
    public boolean add(E e) {
        // 达到容量上限，按策略处理
        if (delegate.size() >= capacity) {
            return handleOverflow(e);
        }
        delegate.addLast(e);
        return true;
    }

    @Override
    /** 移除 */
    public boolean remove(Object o) {
        return delegate.remove(o);
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
    /** 移除All */
    public boolean removeAll(Collection<?> c) {
        boolean modified = false;
        for (Object o : c) {
            modified |= remove(o);
        }
        return modified;
    }

    @Override
    /** RetainAll */
    public boolean retainAll(Collection<?> c) {
        boolean modified = false;
        Iterator<E> iterator = delegate.iterator();
        while (iterator.hasNext()) {
            E element = iterator.next();
            if (!c.contains(element)) {
                iterator.remove();
                modified = true;
            }
        }
        return modified;
    }

    @Override
    /** Clear */
    public void clear() {
        delegate.clear();
    }

    @Override
    /** ToString */
    public String toString() {
        return delegate.toString();
    }

    /**
     * 处理容量满时的溢出逻辑。
     *
     * @param e 待添加的新元素
     * @return 是否成功录入
     */
    private boolean handleOverflow(E e) {
        switch (policy) {
            case REJECT -> {
                // 拒绝录入，直接返回 false
                return false;
            }
            case EVICT_ELDEST -> {
                // 移除最早元素（队首），腾出空间
                delegate.pollFirst();
                delegate.addLast(e);
                return true;
            }
            case EVICT_NEWEST -> {
                // 移除最新元素（队尾），再录入新元素
                delegate.pollLast();
                delegate.addLast(e);
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
