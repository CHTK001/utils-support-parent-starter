package com.chua.common.support.collection;

import java.util.AbstractCollection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
* 环状集合实现，固定容量，超出容量时自动淘汰最早元素。
*
* <p>与 {@link CircularArrayList}（覆盖式环形 List）不同，本集合在容量满时
* 按 {@link OverflowPolicy#EVICT_ELDEST} 语义移除最早加入的元素再录入新元素，
* 保持"保留最近 N 个、最旧先出"的 FIFO 滑动窗口特性，并记录最近被淘汰的元素。
* 内部用定长数组 + 头指针实现环状遍历，单次 add O(1)。</p>
*
* <h3>使用示例</h3>
* <pre>{@code
* CircularCollection<String> c = CircularCollection.of(3);
* c.add("a"); c.add("b"); c.add("c");
* c.add("d");   // 容量 3，淘汰 "a"，内容 [b, c, d]
* c.lastEvicted(); // "a"
* }</pre>
*
* <p>线程不安全，多线程环境请自行加锁或使用 {@link java.util.Collections#synchronizedCollection} 包装。</p>
*
* @param <E> 元素类型
* @author CH
* @since 4.0.0.42
* @version 1.0.0
* @see BoundedCollection
* @see OverflowPolicy
 */
public class CircularCollection<E> extends AbstractCollection<E> implements BoundedCollection<E> {

    /**
    * 内部存储数组
     */
    private final Object[] elements;

    /**
    * 队首索引，指向最早加入的元素
     */
    private int head;

    /**
    * 当前元素数量
     */
    private int size;

    /**
    * 集合固定容量
     */
    private final int capacity;

    /**
    * 当前溢出策略
     */
    private OverflowPolicy policy;

    /**
    * 最近一次因容量满而被淘汰的元素，未触发淘汰时为 null
     */
    private E lastEvicted;

    /**
    * 使用默认策略（删除最早）创建环状集合。
    *
    * @param capacity 容量，必须大于 0
    * @param <E>      元素类型
    * @return 环状集合实例
    * @throws IllegalArgumentException 如果 capacity 小于等于 0
     */
    public static <E> CircularCollection<E> of(int capacity) {
        return new CircularCollection<>(capacity, OverflowPolicy.EVICT_ELDEST);
    }

    /**
    * 使用指定容量和溢出策略创建环状集合。
    *
    * @param capacity 容量，必须大于 0
    * @param policy   溢出策略，不允许为 null
    * @param <E>      元素类型
    * @return 环状集合实例
    * @throws IllegalArgumentException 如果 capacity 小于等于 0
    * @throws NullPointerException     如果 policy 为 null
     */
    public static <E> CircularCollection<E> of(int capacity, OverflowPolicy policy) {
        return new CircularCollection<>(capacity, policy);
    }

    /**
    * 构造方法。
    *
    * @param capacity 容量，必须大于 0
    * @param policy   溢出策略，不允许为 null
    * @throws IllegalArgumentException 如果 capacity 小于等于 0
    * @throws NullPointerException     如果 policy 为 null
     */
    public CircularCollection(int capacity, OverflowPolicy policy) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须大于 0");
        }
        if (policy == null) {
            throw new NullPointerException("溢出策略不允许为 null");
        }
        this.capacity = capacity;
        this.policy = policy;
        this.elements = new Object[capacity];
        this.head = 0;
        this.size = 0;
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
            throw new NoSuchElementException("环状集合为空");
        }
        return (E) elements[head];
    }

    @Override
    /** 取出Eldest */
    public E pollEldest() {
        if (isEmpty()) {
            throw new NoSuchElementException("环状集合为空");
        }
        E e = (E) elements[head];
        elements[head] = null;
        head = (head + 1) % capacity;
        size--;
        return e;
    }

    /**
    * 查看并返回最近一次因容量满而被淘汰的元素。
    *
    * @return 最近被淘汰的元素，未触发淘汰时返回 null
     */
    public E lastEvicted() {
        return lastEvicted;
    }

    @Override
    /** 获取大小 */
    public int size() {
        return size;
    }

    @Override
    /** 是否Empty */
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    /** Contains */
    public boolean contains(Object o) {
        for (int i = 0; i < size; i++) {
            if (Objects.equals(elements[(head + i) % capacity], o)) {
                return true;
            }
        }
        return false;
    }

    @Override
    /** Iterator（按加入顺序：最早 -> 最新） */
    public Iterator<E> iterator() {
        return new Iterator<E>() {
            /** 当前偏移量（相对队首） */
            private int offset = 0;

            @Override
            /** 是否拥有Next */
            public boolean hasNext() {
                return offset < size;
            }

            @Override
            /** Next */
            public E next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                E e = (E) elements[(head + offset) % capacity];
                offset++;
                return e;
            }

            @Override
            /** 移除 */
            public void remove() {
                throw new UnsupportedOperationException("环状集合迭代器不支持 remove()");
            }
        };
    }

    @Override
    /** Clear */
    public void clear() {
        for (int i = 0; i < size; i++) {
            elements[(head + i) % capacity] = null;
        }
        head = 0;
        size = 0;
        lastEvicted = null;
    }

    @Override
    /** 添加（队尾入，按策略处理容量满溢出） */
    public boolean add(E e) {
        int tail = (head + size) % capacity;
        if (size < capacity) {
            elements[tail] = e;
            size++;
            lastEvicted = null;
            return true;
        }
        return handleOverflow(e, tail);
    }

    /**
    * 处理容量满时的溢出逻辑。
    *
    * @param e    待加入的新元素
    * @param tail 队尾索引
    * @return 是否成功加入
     */
    private boolean handleOverflow(E e, int tail) {
        switch (policy) {
            case EVICT_ELDEST -> {
                lastEvicted = (E) elements[head];
                elements[head] = null;
                head = (head + 1) % capacity;
                elements[tail] = e;
                return true;
            }
            case EVICT_NEWEST -> {
                lastEvicted = (E) elements[tail];
                elements[tail] = e;
                return true;
            }
            case REJECT -> {
                lastEvicted = null;
                return false;
            }
            default -> {
                lastEvicted = null;
                return false;
            }
        }
    }
}
