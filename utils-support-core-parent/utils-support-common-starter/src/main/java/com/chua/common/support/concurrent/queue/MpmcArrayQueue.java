package com.chua.common.support.concurrent.queue;

import java.util.concurrent.atomic.AtomicLong;

/**
* 多生产者多消费者（MPMC）有界无锁环形数组队列。
*
* <p>采用 Dmitry Vyukov 提出的无锁有界环形缓冲算法：
* <ul>
*   <li>每个槽位绑定一个原子序号 {@code sequence}（初始值等于槽位下标），
*       通过序号与生产/消费游标的比对判定槽位可写或可读</li>
*   <li>生产者先抢占 {@code producerIndex}（CAS），再写入元素并发布新序号；
*       消费者先抢占 {@code consumerIndex}，再读取元素并释放槽位（序号推进一个容量）</li>
*   <li>多线程入队/出队时通过 CAS 语义保证每个槽位同一时刻只被一个线程持有</li>
* </ul>
* </p>
*
* <p>适用场景：任意数量的生产者与消费者共享的有界消息缓冲，例如任务分发、日志缓冲、
* 交易撮合等需要严格有界且高吞吐的场合。</p>
*
* @param <E> 队列元素类型
* @author CH
* @since 4.0.0.42
 */
@SuppressWarnings("unused")
public class MpmcArrayQueue<E> implements LockFreeQueue<E> {

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
    * 环形槽位数组，每个槽位持有独立的原子序号
    */
    private final RingCell<E>[] cells;

    // ==================== 生产者游标（独立缓存行） ====================

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
    * 生产者游标，表示下一个待写入槽位的全局序号，多个生产者通过 CAS 抢占
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

    // ==================== 消费者游标（独立缓存行） ====================

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
    * 消费者游标，表示下一个待读取槽位的全局序号，多个消费者通过 CAS 抢占
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
    * 创建指定容量的 MPMC 无锁环形队列。
    *
    * @param requestedCapacity 期望容量，自动向上对齐为 2 的幂（至少 2）
    */
    @SuppressWarnings("unchecked")
    public MpmcArrayQueue(int requestedCapacity) {
        this.capacity = alignToPowerOfTwo(requestedCapacity);
        this.mask = this.capacity - 1;
        this.cells = (RingCell<E>[]) new RingCell<?>[this.capacity];
        // 槽位序号初始化为各自下标，表示从 0 号槽开始可写
        for (int i = 0; i < this.capacity; i++) {
            this.cells[i] = new RingCell<>(i);
        }
    }

    /**
    * 入队一个元素，由任意生产者线程调用。
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
        while (true) {
            long pi = producerIndex.getPlain();
            RingCell<E> cell = cells[(int) (pi & mask)];
            long seq = cell.sequence.getAcquire();
            long diff = seq - pi;
            if (diff == 0) {
                // 该槽位空闲且轮到当前游标，CAS 抢占成功后写入并发布新序号
                if (producerIndex.compareAndSet(pi, pi + 1)) {
                    cell.value = element;
                    cell.sequence.setRelease(pi + 1);
                    return true;
                }
            } else if (diff < 0) {
                // 槽位尚未被消费者回收，队列已满
                return false;
            }
            // diff > 0：槽位被其他生产者暂时占用，自旋重试
        }
    }

    /**
    * 出队并移除队首元素，由任意消费者线程调用。
    *
    * @return 队首元素；队列为空时返回 null
    */
    @Override
    public E poll() {
        while (true) {
            long ci = consumerIndex.getPlain();
            RingCell<E> cell = cells[(int) (ci & mask)];
            long seq = cell.sequence.getAcquire();
            long diff = seq - (ci + 1);
            if (diff == 0) {
                // 槽位已被生产者填充，CAS 抢占成功后读取并释放槽位
                if (consumerIndex.compareAndSet(ci, ci + 1)) {
                    E value = cell.value;
                    // 释放槽位：序号推进一个容量，便于生产者复用
                    cell.sequence.setRelease(ci + capacity);
                    return value;
                }
            } else if (diff < 0) {
                // 槽位尚未被生产者填充，队列为空
                return null;
            }
            // diff > 0：槽位被其他消费者暂时持有，自旋重试
        }
    }

    /**
    * 查看队首元素但不移除（近似、随时效，仅供监控）。
    * <p>MPMC 环形队列的槽位由 CAS 独占，本方法不做线性化保证，
    * 仅在环境中断言队首槽位已就绪时返回其值。</p>
    *
    * @return 队首元素；队列为空时返回 null
    */
    @Override
    public E peek() {
        long ci = consumerIndex.getPlain();
        RingCell<E> cell = cells[(int) (ci & mask)];
        long seq = cell.sequence.getAcquire();
        if (seq - (ci + 1) == 0) {
            return cell.value;
        }
        return null;
    }

    /**
    * 判断队列是否为空（近似即可）。
    *
    * @return 队列为空返回 true
    */
    @Override
    public boolean isEmpty() {
        return producerIndex.getPlain() - consumerIndex.getPlain() <= 0;
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

    /**
    * 环形槽位单元，持有独立原子序号与元素引用。
    *
    * @param <E> 元素类型
    */
    private static final class RingCell<E> {

        /**
        * 槽位原子序号，控制该槽位的可写/可读状态
        */
        private final AtomicLong sequence = new AtomicLong();

        /**
        * 槽位中存储的元素值
        */
        private volatile E value;

        /**
        * 构造环形槽位。
        *
        * @param initialSequence 初始序号，等于槽位下标
        */
        RingCell(long initialSequence) {
            sequence.set(initialSequence);
        }
    }
}
