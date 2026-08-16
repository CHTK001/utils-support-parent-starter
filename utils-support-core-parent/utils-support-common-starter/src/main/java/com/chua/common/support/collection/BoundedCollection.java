package com.chua.common.support.collection;

import java.util.Collection;

/**
 * 有界集合接口，继承 {@link Collection}。
 *
 * <p>在 {@link Collection} 基础上提供固定容量与可配置的溢出策略。当元素数量达到容量上限后，
 * 继续添加新元素的行为由 {@link OverflowPolicy} 决定：拒绝录入、删除最早或删除最新。</p>
 *
 * <h3>适用场景</h3>
 * <ul>
 *   <li><strong>固定容量缓存</strong> — 有界内存缓存，容量确定、淘汰策略可配置</li>
 *   <li><strong>滑动窗口</strong> — 固定窗口内统计，新进旧出（{@link OverflowPolicy#EVICT_ELDEST}）</li>
 *   <li><strong>黑名单/白名单</strong> — 容量固定，满了拒绝录入（{@link OverflowPolicy#REJECT}）</li>
 * </ul>
 *
 * @param <E> 元素类型
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 * @see OverflowPolicy
 */
public interface BoundedCollection<E> extends Collection<E> {

    /**
     * 返回集合的固定容量（最大可容纳元素数量）。
     *
     * @return 集合容量
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
     * 返回最早加入的元素（不移除）。
     *
     * @return 最早加入的元素
     * @throws java.util.NoSuchElementException 如果集合为空
     */
    E peekEldest();

    /**
     * 移除最早加入的元素。
     *
     * @return 被移除的最早元素
     * @throws java.util.NoSuchElementException 如果集合为空
     */
    E pollEldest();
}
