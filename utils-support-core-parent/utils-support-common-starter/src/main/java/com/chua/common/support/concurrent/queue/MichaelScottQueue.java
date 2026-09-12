package com.chua.common.support.concurrent.queue;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
* Michael-Scott 无锁队列，无界，支持多生产者多消费者（MPMC）。
*
* <p>基于 1996 年 Maged M. Michael 与 Michael L. Scott 提出的经典无锁 FIFO 队列算法：
* <ul>
*   <li>链表首个节点为哨兵（dummy）节点，其 {@code next} 指向队首元素，出队通过 CAS 前进 head</li>
*   <li>入队通过 CAS 追加新节点到 tail，失败时先推进滞后 tail 再重试</li>
*   <li>全程只使用 {@link AtomicLong} / {@link AtomicReference} 的 CAS，不使用任何悲观锁</li>
* </ul>
* </p>
*
* <p>适用场景：队列长度无法预估、需要反复扩展缩容的消息缓冲；相比环形队列牺牲一部分吞吐
* 换取无容量限制。</p>
*
* @param <E> 队列元素类型
* @author CH
* @since 4.0.0.42
 */
public class MichaelScottQueue<E> implements LockFreeQueue<E> {

    /**
    * size() 遍历时的最大扫描节点数保护值，防止极端并发下无限遍历
     */
    private static final int MAX_SIZE_SCAN = 1_000_000;

    /**
    * 队列头引用，指向哨兵或已出队节点，其 next 为队首元素
     */
    private final AtomicReference<Node<E>> head = new AtomicReference<>();

    /**
    * 队列尾引用，指向最新追加节点
     */
    private final AtomicReference<Node<E>> tail = new AtomicReference<>();

    /**
    * 创建空队列，初始化哨兵节点。
     */
    public MichaelScottQueue() {
        Node<E> sentinel = new Node<>(null);
        head.set(sentinel);
        tail.set(sentinel);
    }

    /**
    * 入队一个元素，追加到链表尾部。
    * <p>无界队列，除非元素为 null，否则始终返回 true。</p>
    *
    * @param element 待入队元素，禁止为 null
    * @return 恒为 true
    * @throws NullPointerException 元素为 null 时抛出
     */
    @Override
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element must not be null");
        }
        Node<E> node = new Node<>(element);
        while (true) {
            Node<E> last = tail.getAcquire();
            Node<E> next = last.next.getAcquire();
            if (next == null) {
                if (last.next.compareAndSet(null, node)) {
                    tail.compareAndSet(last, node);
                    return true;
                }
            } else {
                tail.compareAndSet(last, next);
            }
            Thread.onSpinWait();
        }
    }

    /**
    * 出队并移除队首元素。
    *
    * @return 队首元素；队列为空时返回 null
     */
    @Override
    public E poll() {
        while (true) {
            Node<E> first = head.getAcquire();
            Node<E> last = tail.getAcquire();
            Node<E> next = first.next.getAcquire();
            if (first == last) {
                if (next == null) {
                    return null;
                }
                tail.compareAndSet(last, next);
            } else {
                E value = next.value;
                if (head.compareAndSet(first, next)) {
                    return value;
                }
            }
            Thread.onSpinWait();
        }
    }

    /**
    * 查看队首元素但不移除。
    *
    * @return 队首元素；队列为空时返回 null
     */
    @Override
    public E peek() {
        for (int i = 0; i < 100; i++) {
            Node<E> first = head.getAcquire();
            Node<E> next = first.next.getAcquire();
            if (next == null) {
                return null;
            }
            if (head.getAcquire() == first) {
                return next.value;
            }
            Thread.onSpinWait();
        }
        return null;
    }

    /**
    * 判断队列是否为空。
    *
    * @return 队列为空返回 true
     */
    @Override
    public boolean isEmpty() {
        return head.getAcquire().next.getAcquire() == null;
    }

    @Override
    /** 获取大小 */
    public int size() {
        int count = 0;
        Node<E> node = head.getAcquire().next.getAcquire();
        while (node != null && count < MAX_SIZE_SCAN) {
            count++;
            node = node.next.getAcquire();
        }
        return count;
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
    * 无界队列容量返回 Integer.MAX_VALUE。
    *
    * @return {@link Integer#MAX_VALUE}
     */
    @Override
    public int capacity() {
        return Integer.MAX_VALUE;
    }

    /**
    * 无锁链表节点，next 使用原子引用支撑 CAS 追加。
    *
    * @param <E> 元素类型
     */
    private static final class Node<E> {

        /**
        * 节点存储的元素值，哨兵节点为 null
         */
        private final E value;

        /**
        * 下一个节点的原子引用
         */
        private final AtomicReference<Node<E>> next = new AtomicReference<>();

        /**
        * 构造节点。
        *
        * @param value 元素值，哨兵传入 null
         */
        Node(E value) {
            this.value = value;
        }
    }
}