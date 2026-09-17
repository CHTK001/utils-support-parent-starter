package com.chua.common.support.collection;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;

/**
* 基于固定数组的环状队列实现，固定容量、FIFO。
*
* <p>内部使用定长数组加队首（{@code head}）/队尾（{@code tail}）双指针实现环状存取，
* 空间复杂度 O(capacity)、单次入队/出队 O(1)。当元素数量达到容量上限后，
* 继续 {@link #offer(Object)} 的行为由 {@link OverflowPolicy} 决定：</p>
* <ul>
*   <li>{@link OverflowPolicy#EVICT_ELDEST} — 自动移除最早（队首）元素后入队（滑动窗口默认）</li>
*   <li>{@link OverflowPolicy#REJECT} — 拒绝录入，队满时 offer 返回 false</li>
*   <li>{@link OverflowPolicy#EVICT_NEWEST} — 移除最新元素后入队</li>
* </ul>
*
* <h3>使用示例</h3>
* <pre>{@code
* CircularQueue<String> queue = CircularArrayQueue.of(3);
* queue.offer("a");
* queue.offer("b");
* queue.offer("c");
* queue.offer("d");   // 容量 3，EVICT_ELDEST：淘汰 "a"，实际内容 [b, c, d]
* queue.lastEvicted(); // "a"
* queue.poll();        // "b"
* }</pre>
*
* <p>线程不安全，多线程环境请自行加锁或使用 {@link java.util.Collections#synchronizedCollection} 包装。</p>
*
* @param <E> 元素类型
* @author CH
* @since 4.0.0.42
* @version 1.0.0
* @see CircularQueue
* @see OverflowPolicy
 */
public class CircularArrayQueue<E> extends AbstractQueue<E> implements CircularQueue<E> {

    /**
    * 内部存储数组
    */
    private final Object[] elements;

    /**
    * 队首索引，指向最早入队的元素
    */
    private int head;

    /**
    * 队尾索引，指向下一个入队的位置
    */
    private int tail;

    /**
    * 当前元素数量
    */
    private int size;

    /**
    * 队列固定容量
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
    * 使用默认溢出策略（删除最早）创建环状队列。
    *
    * @param capacity 队列容量，必须大于 0
    * @param <E>      元素类型
    * @return 环状队列实例
    * @throws IllegalArgumentException 如果 capacity 小于等于 0
    */
    public static <E> CircularArrayQueue<E> of(int capacity) {
        return new CircularArrayQueue<>(capacity, OverflowPolicy.EVICT_ELDEST);
    }

    /**
    * 使用指定容量与初始集合创建环状队列，默认策略为删除最早（FIFO 淘汰）。
    * <p>若初始集合大小超过容量，按 EVICT_ELDEST 仅保留最后 capacity 个元素。</p>
    *
    * @param capacity 队列容量，必须大于 0
    * @param c        初始集合，可为 null
    * @param <E>      元素类型
    * @return 环状队列实例
    * @throws IllegalArgumentException 如果 capacity 小于等于 0
    */
    public static <E> CircularArrayQueue<E> of(int capacity, Collection<? extends E> c) {
        CircularArrayQueue<E> queue = new CircularArrayQueue<>(capacity, OverflowPolicy.EVICT_ELDEST);
        if (c != null) {
            for (E e : c) {
                queue.offer(e);
            }
        }
        return queue;
    }

    /**
    * 构造方法。
    *
    * @param capacity 队列容量，必须大于 0
    */
    public CircularArrayQueue(int capacity) {
        this(capacity, OverflowPolicy.EVICT_ELDEST);
    }

    /**
    * 使用指定容量和溢出策略创建环状队列。
    *
    * @param capacity 队列容量，必须大于 0
    * @param policy   溢出策略，不允许为 null
    * @throws IllegalArgumentException 如果 capacity 小于等于 0
    * @throws NullPointerException     如果 policy 为 null
    */
    public CircularArrayQueue(int capacity, OverflowPolicy policy) {
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
        this.tail = 0;
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
    /** LastEvicted */
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
    /** 是否包含（用于 AbstractQueue 的 add 等） */
    public boolean contains(Object o) {
        for (int i = 0; i < size; i++) {
            if (java.util.Objects.equals(elements[(head + i) % capacity], o)) {
                return true;
            }
        }
        return false;
    }

    @Override
    /** Iterator（按入队顺序：队首 -> 队尾） */
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
                throw new UnsupportedOperationException("环状队列迭代器不支持 remove()");
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
        tail = 0;
        size = 0;
        lastEvicted = null;
    }

    @Override
    /** 出队（FIFO，队首），覆盖 AbstractQueue 默认实现 */
    public E poll() {
        if (isEmpty()) {
            throw new NoSuchElementException("环状队列为空");
        }
        E e = (E) elements[head];
        elements[head] = null;
        head = (head + 1) % capacity;
        size--;
        return e;
    }

    @Override
    /**
    * 查看队首（FIFO 头部），覆盖 AbstractQueue 默认实现
    */
    public E peek() {
        if (isEmpty()) {
            throw new NoSuchElementException("环状队列为空");
        }
        return (E) elements[head];
    }

    @Override
    /** 入队（队尾），按策略处理队满溢出 */
    public boolean offer(E e) {
        if (size < capacity) {
            elements[tail] = e;
            tail = (tail + 1) % capacity;
            size++;
            lastEvicted = null;
            return true;
        }
        return handleOverflow(e);
    }

    /**
    * 处理队满时的溢出逻辑。
    *
    * @param e 待入队的新元素
    * @return 是否成功入队
    */
    private boolean handleOverflow(E e) {
        switch (policy) {
            case EVICT_ELDEST -> {
                lastEvicted = (E) elements[head];
                elements[head] = null;
                head = (head + 1) % capacity;
                elements[tail] = e;
                tail = (tail + 1) % capacity;
                return true;
            }
            case EVICT_NEWEST -> {
                int newestSlot = (tail - 1 + capacity) % capacity;
                lastEvicted = (E) elements[newestSlot];
                elements[newestSlot] = null;
                tail = newestSlot;
                elements[tail] = e;
                tail = (tail + 1) % capacity;
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
