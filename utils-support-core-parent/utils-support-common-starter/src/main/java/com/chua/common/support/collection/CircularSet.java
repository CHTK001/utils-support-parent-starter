package com.chua.common.support.collection;

import java.util.Set;

/**
* 环状集合接口，继承 {@link Set}。
* <p>
* 在 {@link Set} 基础上提供固定容量和自动淘汰最旧元素的能力。
* 当元素数量达到容量上限后，继续添加新元素的行为由 {@link OverflowPolicy} 决定。
* </p>
*
* <h3>适用场景</h3>
* <ul>
*   <li><strong>去重滑动窗口</strong> — 固定窗口内去重统计，如最近访问过的 IP/用户 ID</li>
*   <li><strong>最近访问记录</strong> — 固定容量记录最近访问的 Key，用于热点统计</li>
*   <li><strong>有限去重缓存</strong> — 有限大小且不允许重复的缓存，超出后按策略淘汰</li>
* </ul>
*
* @param <E> 元素类型
* @author CH
* @since 4.0.0.42
* @version 1.0.0
* @see CircularLinkedSet
* @see OverflowPolicy
 */
public interface CircularSet<E> extends Set<E> {

    /**
    * 查看并返回最早加入的元素（不移除）。
    *
    * @return 最早加入的元素
    * @throws java.util.NoSuchElementException 如果集合为空
    */
    E peekEldest();

    /**
    * 移除并返回最早加入的元素。
    *
    * @return 最早加入的元素
    * @throws java.util.NoSuchElementException 如果集合为空
    */
    E pollEldest();

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
    * 返回最近一次因容量满而被淘汰的元素。
    * <p>仅在最近一次 {@link #add(Object)} 真正触发淘汰时返回被淘汰元素；
    * 若该次 add 未触发淘汰，则返回 null。适用于调用方判断"本次 add 是否淘汰了元素"。</p>
    *
    * @return 最近被淘汰的元素，本次 add 未触发淘汰时返回 null
    */
    E lastEvicted();
}
