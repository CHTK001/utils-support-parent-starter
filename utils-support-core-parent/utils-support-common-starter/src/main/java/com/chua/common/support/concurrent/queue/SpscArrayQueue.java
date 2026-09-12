package com.chua.common.support.concurrent.queue;

import java.util.concurrent.atomic.AtomicLong;

/**
* 单生产者单消费者（SPSC）无锁环形数组队列，吞吐最高的无锁队列。
*
* <p>核心特性：
* <ul>
*   <li>容量自动向上对齐为 2 的幂，槽位定位使用 {@code index & (capacity - 1)} 位运算</li>
*   <li>生产者仅写 {@code producerIndex}，消费者仅写 {@code consumerIndex}，二者互不竞争索引</li>
*   <li>借助 release/acquire 内存语义，先写槽位再发布索引，避免 8KB 范围之外的伪共享</li>
*   <li>各索引间通过缓存行填充（padding）隔离，避免因伪共享拖垮性能</li>
* </ul>
* </p>
*
* <p>适用约束：<b>必须严格保证单生产者 + 单消费者</b>，即同一时刻只有
* 一个线程调用 {@link #offer(Object)}，只有一个线程调用 {@link #poll()}。
* 违反该约束会破坏队列正确性，多线程场景请改用 {@link MpmcArrayQueue}。</p>
*
* @param <E> 队列元素类型
* @author CH
* @since 4.0.0.42
 */
@SuppressWarnings("unused")
public class SpscArrayQueue<E> implements LockFreeQueue<E> {

    /**
    * 最小容量，避免构造 0/1 容量的非法队列
     */
    private static final int MIN_CAPACITY = 2;

    /**
    * 最大容量，2^30，防止数组过大
     */
    private static final int MAX_CAPACITY = 1 << 30;

    /**
    * 实际容量（2 的幂）
     */
    private final int capacity;

    /**
    * 容量减一，用于槽位定位的掩码
     */
    private final int mask;

    /**
    * 环形数组，槽位存真实元素，具体位置由索引与掩码运算得出
     */
    private final Object[] buffer;

    // ==================== 生产者索引（独立缓存行） ====================

    /**
    * 缓存行填充，隔离 producerIndex 与 consumerIndex
     */
    private long producerPad1;

    /**
    * 缓存行填充，隔离 producerIndex 与 consumerIndex
     */
    private long producerPad2;

    /**
    * 缓存行填充，隔离 producerIndex 与 consumerIndex
     */
    private long producerPad3;

    /**
    * 缓存行填充，隔离 producerIndex 与 consumerIndex
     */
    private long producerPad4;

    /**
    * 缓存行填充，隔离 producerIndex 与 consumerIndex
     */
    private long producerPad5;

    /**
    * 缓存行填充，隔离 producerIndex 与 consumerIndex
     */
    private long producerPad6;

    /**
    * 缓存行填充，隔离 producerIndex 与 consumerIndex
     */
    private long producerPad7;

    /**
    * 生产者索引，表示下一个待写入的槽位序号，仅由生产者写、消费者读
     */
    private final AtomicLong producerIndex = new AtomicLong();

    /**
    * 缓存行填充，隔离 producerIndex 与 consumerIndex
     */
    private long producerPad9;

    /**
    * 缓存行填充，隔离 producerIndex 与 consumerIndex
     */
    private long producerPad10;

    /**
    * 缓存行填充，隔离 producerIndex 与 consumerIndex
     */
    private long producerPad11;

    /**
    * 缓存行填充，隔离 producerIndex 与 consumerIndex
     */
    private long producerPad12;

    /**
    * 缓存行填充，隔离 producerIndex 与 consumerIndex
     */
    private long producerPad13;

    /**
    * 缓存行填充，隔离 producerIndex 与 consumerIndex
     */
    private long producerPad14;

    /**
    * 缓存行填充，隔离 producerIndex 与 consumerIndex
     */
    private long producerPad15;

    // ==================== 消费者索引（独立缓存行） ====================

    /**
    * 缓存行填充，隔离 consumerIndex 与 producerIndex
     */
    private long consumerPad1;

    /**
    * 缓存行填充，隔离 consumerIndex 与 producerIndex
     */
    private long consumerPad2;

    /**
    * 缓存行填充，隔离 consumerIndex 与 producerIndex
     */
    private long consumerPad3;

    /**
    * 缓存行填充，隔离 consumerIndex 与 producerIndex
     */
    private long consumerPad4;

    /**
    * 缓存行填充，隔离 consumerIndex 与 producerIndex
     */
    private long consumerPad5;

    /**
    * 缓存行填充，隔离 consumerIndex 与 producerIndex
     */
    private long consumerPad6;

    /**
    * 缓存行填充，隔离 consumerIndex 与 producerIndex
     */
    private long consumerPad7;

    /**
    * 消费者索引，表示下一个待读取的槽位序号，仅由消费者写、生产者读
     */
    private final AtomicLong consumerIndex = new AtomicLong();

    /**
    * 缓存行填充，隔离 consumerIndex 与 producerIndex
     */
    private long consumerPad9;

    /**
    * 缓存行填充，隔离 consumerIndex 与 producerIndex
     */
    private long consumerPad10;

    /**
    * 缓存行填充，隔离 consumerIndex 与 producerIndex
     */
    private long consumerPad11;

    /**
    * 缓存行填充，隔离 consumerIndex 与 producerIndex
     */
    private long consumerPad12;

    /**
    * 缓存行填充，隔离 consumerIndex 与 producerIndex
     */
    private long consumerPad13;

    /**
    * 缓存行填充，隔离 consumerIndex 与 producerIndex
     */
    private long consumerPad14;

    /**
    * 缓存行填充，隔离 consumerIndex 与 producerIndex
     */
    private long consumerPad15;

    /**
    * 生产者内部缓存的消费者索引，减少入队时对共享索引的读取
     */
    private long consumerCached;

    /**
    * 消费者内部缓存的生产者索引，减少出队时对共享索引的读取
     */
    private long producerCached;

    /**
    * 创建指定容量的 SPSC 无锁环形队列。
    *
    * @param requestedCapacity 期望容量，自动向上对齐为 2 的幂（至少 2）
     */
    public SpscArrayQueue(int requestedCapacity) {
        this.capacity = alignToPowerOfTwo(requestedCapacity);
        this.mask = this.capacity - 1;
        this.buffer = new Object[this.capacity];
    }

    /**
    * 入队一个元素，由唯一的生产者线程调用。
    *
    * @param element 待入队元素，禁止为 null
    * @return 入队成功返回 true；队列已满返回 false
    * @throws NullPointerException 元素为 null 时抛出
     */
    @Override
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element must not be null");
        }
        long pi = producerIndex.getPlain();
        long ci = consumerCached;
        if (pi - ci >= capacity) {
            // 缓存过期，刷新消费者索引后再次判断
            ci = consumerIndex.getAcquire();
            consumerCached = ci;
            if (pi - ci >= capacity) {
                // 队列已满
                return false;
            }
        }
        buffer[(int) (pi & mask)] = element;
        // 先写槽位，再发布生产者索引（release 语义保证槽位写入先行可见）
        producerIndex.setRelease(pi + 1);
        return true;
    }

    /**
    * 出队并移除队首元素，由唯一的消费者线程调用。
    *
    * @return 队首元素；队列为空时返回 null
     */
    @Override
    @SuppressWarnings("unchecked")
    public E poll() {
        long ci = consumerIndex.getPlain();
        long pi = producerCached;
        if (ci - pi >= 0) {
            // 缓存过期，刷新生产者索引后再次判断
            pi = producerIndex.getAcquire();
            producerCached = pi;
            if (ci - pi >= 0) {
                // 队列为空
                return null;
            }
        }
        E element = (E) buffer[(int) (ci & mask)];
        // 先读槽位，再发布消费者索引（release 语义保证生产者可复用该槽位）
        consumerIndex.setRelease(ci + 1);
        return element;
    }

    /**
    * 查看队首元素但不移除，由唯一的消费者线程调用。
    *
    * @return 队首元素；队列为空时返回 null
     */
    @Override
    @SuppressWarnings("unchecked")
    public E peek() {
        long ci = consumerIndex.getPlain();
        long pi = producerCached;
        if (ci - pi >= 0) {
            pi = producerIndex.getAcquire();
            producerCached = pi;
            if (ci - pi >= 0) {
                return null;
            }
        }
        return (E) buffer[(int) (ci & mask)];
    }

    /**
    * 判断队列是否为空，由任意线程调用，允许轻微即时性偏差。
    *
    * @return 队列为空返回 true
     */
    @Override
    public boolean isEmpty() {
        return consumerIndex.getPlain() >= producerIndex.getPlain();
    }

    /**
    * 返回队列中的元素数量（近似值）。
    *
    * @return 元素数量估计值
     */
    @Override
    public int size() {
        long diff = producerIndex.getPlain() - consumerIndex.getPlain();
        if (diff <= 0) {
            return 0;
        }
        return (int) Math.min(diff, capacity);
    }

    /**
    * 清空队列，持续出队直至为空。
     */
    @Override
    public void clear() {
        while (poll() != null) {
            // 持续出队直至队列为空
        }
    }

    /**
    * 返回队列容量（对齐后的 2 的幂）。
    *
    * @return 队列容量
     */
    @Override
    public int capacity() {
        return capacity;
    }

    /**
    * 将期望容量向上对齐为 2 的幂。
    *
    * @param requested 期望容量
    * @return 对齐后的容量（不小于 2）
    * @throws IllegalArgumentException 容量超过最大限制时抛出
     */
    private static int alignToPowerOfTwo(int requested) {
        if (requested < MIN_CAPACITY) {
            requested = MIN_CAPACITY;
        }
        if (requested > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity 超过最大值: " + MAX_CAPACITY);
        }
        return Integer.highestOneBit(requested - 1) << 1;
    }
}