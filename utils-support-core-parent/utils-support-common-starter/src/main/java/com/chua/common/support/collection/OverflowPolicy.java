package com.chua.common.support.collection;

/**
 * 有界集合溢出策略。
 *
 * <p>定义当元素数量达到容量上限后，继续添加新元素时的处理方式：</p>
 * <ul>
 *   <li><strong>{@link #REJECT}</strong> — 拒绝录入：丢弃新元素，已存在元素保持不变</li>
 *   <li><strong>{@link #EVICT_ELDEST}</strong> — 删除最早：移除最旧的元素，为新元素腾出空间</li>
 *   <li><strong>{@link #EVICT_NEWEST}</strong> — 删除最新：移除最新的元素，保留更早加入的元素</li>
 * </ul>
 *
 * <p>适用于固定容量缓存、滑动窗口、日志环形缓冲等"容量有界、行为可预期"的场景。</p>
 *
 * @author CH
 * @version 1.0.0
 * @see BoundedCollection
 */
public enum OverflowPolicy {

    /**
     * 拒绝录入
     * <p>达到容量上限后，新元素被丢弃，集合内容不变。适合"满了就不再收录"的名单类场景。</p>
     */
    REJECT,

    /**
     * 删除最早
     * <p>达到容量上限后，自动移除最早加入的元素（FIFO 淘汰），再录入新元素。适合滑动窗口、最近记录。</p>
     */
    EVICT_ELDEST,

    /**
     * 删除最新
     * <p>达到容量上限后，自动移除最新加入的元素，再录入新元素。适合保留"历史稳定集"、忽略突发新增。</p>
     */
    EVICT_NEWEST;
}
