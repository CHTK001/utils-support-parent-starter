package com.chua.common.support.collection;

/**
 * 有界集合溢出策略。
 *
 * <p>当集合达到容量上限后，继续添加新元素时触发的行为策略：</p>
 * <ul>
 *   <li>{@link #REJECT} — 拒绝新元素，不添加，已存在元素不受影响</li>
 *   <li>{@link #EVICT_ELDEST} — 淘汰最旧元素后添加新元素</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum OverflowPolicy {

    /**
     * 拒绝策略：达到容量上限后拒绝新元素加入。
     */
    REJECT,

    /**
     * 淘汰最旧策略：达到容量上限后移除最旧元素，再添加新元素。
     */
    EVICT_ELDEST
}