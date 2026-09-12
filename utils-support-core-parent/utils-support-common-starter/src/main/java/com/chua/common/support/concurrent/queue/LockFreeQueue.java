package com.chua.common.support.concurrent.queue;

/**
* 无锁队列统一接口。
*
* <p>该接口定义无锁（Lock-Free）队列的标准操作，所有实现均不阻塞线程：
* <ul>
*   <li>{@link #offer(Object)} 入队，队满时立即返回 false，不会阻塞/自旋等待</li>
*   <li>{@link #poll()} 出队，队空时立即返回 null</li>
*   <li>所有实现基于 CAS（比较并交换）等原子操作，不使用任何 {@code synchronized} 锁</li>
* </ul>
* </p>
*
* <p>内置三种无锁实现，可通过 {@link LockFreeQueueFlow} 统一创建：
* <ul>
*   <li>{@link SpscArrayQueue} — 单生产者单消费者环形数组，吞吐最高（>=2 的幂容量）</li>
*   <li>{@link MpmcArrayQueue} — 多生产者多消费者有界环形数组（Vyukov 算法）</li>
*   <li>{@link MichaelScottQueue} — Michael-Scott 无界链表队列，适合无所谓容量的场景</li>
* </ul>
* </p>
*
* <p>约定：所有实现禁止放入 {@code null} 元素，放入时抛出 {@link NullPointerException}。</p>
*
* @param <E> 队列元素类型
* @author CH
* @since 4.0.0.42
 */
public interface LockFreeQueue<E> {

    /**
    * 入队一个元素。
    * <p>有界实现队满时返回 false，无界实现始终返回 true。禁止放入 null。</p>
    *
    * @param element 待入队元素，禁止为 null
    * @return 入队成功返回 true；有界队列已满时返回 false
    * @throws NullPointerException 元素为 null 时抛出
     */
    boolean offer(E element);

    /**
    * 出队并移除队首元素。
    *
    * @return 队首元素；队列为空时返回 null
     */
    E poll();

    /**
    * 查看队首元素但不移除。
    *
    * @return 队首元素；队列为空时返回 null
     */
    E peek();

    /**
    * 判断队列是否为空。
    *
    * @return 队列为空返回 true
     */
    boolean isEmpty();

    /**
    * 返回当前队列中的元素数量。
    * <p>无锁队列的 size 通常是近似值（O(1) 基于索引差），仅供参考。</p>
    *
    * @return 元素数量估计值
     */
    int size();

    /**
    * 清空队列中的所有元素。
     */
    void clear();

    /**
    * 返回队列容量。
    * <p>有界环形队列返回固定容量；无界队列返回 {@link Integer#MAX_VALUE}。</p>
    *
    * @return 队列容量
     */
    default int capacity() {
        return Integer.MAX_VALUE;
    }
}