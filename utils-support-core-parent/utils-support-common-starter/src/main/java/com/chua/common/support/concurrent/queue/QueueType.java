package com.chua.common.support.concurrent.queue;

/**
 * 无锁队列类型枚举。
 *
 * <p>用于 {@link QueueConfig} 中选择无锁队列的具体实现：
 * <ul>
 *   <li>{@link #SPSC} — 单生产者单消费者环形队列，要求只有一个生产线程和一个消费线程</li>
 *   <li>{@link #MPMC} — 多生产者多消费者有界环形队列，任意数量的生产/消费线程</li>
 *   <li>{@link #UNBOUNDED} — Michael-Scott 无界链表队列，不限制容量且支持多生产者多消费者</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum QueueType {

    /**
     * 单生产者单消费者（Single Producer Single Consumer）
     */
    SPSC,

    /**
     * 多生产者多消费者（Multiple Producer Multiple Consumer）
     */
    MPMC,

    /**
     * 无界（Unbounded）
     */
    UNBOUNDED
}