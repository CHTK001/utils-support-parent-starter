package com.chua.common.support.collection;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;

/**
* 环状队列接口，继承 {@link Queue}。
* <p>
* 固定容量的 FIFO 环形缓冲区：元素从队尾入队、从队首出队。
* 当元素数量达到容量上限后，继续入队的行为由 {@link OverflowPolicy} 决定：
* </p>
* <ul>
*   <li>{@link OverflowPolicy#EVICT_ELDEST} — 自动移除最早（队首）元素后入队（滑动窗口默认行为）</li>
*   <li>{@link OverflowPolicy#REJECT} — 拒绝录入，队满时 {@link #offer(Object)} 返回 false</li>
*   <li>{@link OverflowPolicy#EVICT_NEWEST} — 移除最新元素后入队（保留稳定历史集）</li>
* </ul>
*
* <h3>适用场景</h3>
* <ul>
*   <li><strong>固定大小环形缓冲区</strong> — 生产者/消费者模型中固定容量、无锁自管的读写缓冲</li>
*   <li><strong>最近 N 条日志/事件</strong> — 保留最近记录，最旧自动淘汰</li>
*   <li><strong>限流/滑动窗口计数</strong> — 固定窗口内统计，新进旧出</li>
* </ul>
*
* <p>线程不安全，多线程环境请自行加锁或使用 {@link java.util.Collections#synchronizedCollection} 包装。</p>
*
* @param <E> 元素类型
* @author CH
* @since 4.0.0.42
* @version 1.0.0
* @see CircularQueue
* @see CircularArrayQueue
* @see OverflowPolicy
 */
public interface CircularQueue<E> extends Queue<E> {

    /**
    * 返回队列的固定容量（最大可容纳元素数量）。
    *
    * @return 队列容量
     */
    int capacity();

    /**
    * 返回当前溢出策略。
    *
    * @return 溢出策略
     */
    OverflowPolicy policy();

    /**
    * 修改溢出策略。
    *
    * @param policy 新的溢出策略，不允许为 null
     */
    void setPolicy(OverflowPolicy policy);

    /**
    * 入队（从队尾加入），按 {@link #policy()} 处理队满溢出。
    *
    * @param e 元素
    * @return 是否成功入队；{@link OverflowPolicy#REJECT} 策略下队满时返回 false，其余返回 true
     */
    @Override
    boolean offer(E e);

    /**
    * 出队（从队首移除）。
    *
    * @return 队首元素
    * @throws NoSuchElementException 如果队列为空
     */
    @Override
    E poll();

    /**
    * 查看并返回最近一次因容量满而被淘汰的元素。
    * <p>仅在最近一次 {@link #offer(Object)} 真正触发淘汰时返回被淘汰元素；
    * 若该次 offer 未触发淘汰，则返回 null。</p>
    *
    * @return 最近被淘汰的元素，本次 offer 未触发淘汰时返回 null
     */
    E lastEvicted();
}
