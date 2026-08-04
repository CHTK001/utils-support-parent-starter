package com.chua.common.support.collection;

import java.util.Collection;
import java.util.Set;
import org.jspecify.annotations.NullUnmarked;

/**
 * 环状集合接口，继承 {@link Set}。
 * <p>
 * 在 {@link Set} 基础上提供固定容量和自动淘汰最旧元素的能力。
 * 当元素数量达到容量上限后，继续添加新元素会导致最旧的元素被自动移除。
 * </p>
 *
 * <h3>适用场景</h3>
 * <ul>
 *   <li><strong>去重滑动窗口</strong> — 固定窗口内去重统计，如最近访问过的 IP/用户 ID</li>
 *   <li><strong>最近访问记录</strong> — 固定容量记录最近访问的 Key，用于热点统计</li>
 *   <li><strong>有限去重缓存</strong> — 有限大小且不允许重复的缓存，超出后自动淘汰最早加入项</li>
 * </ul>
 *
 * @param <E> 元素类型
 * @author CH
 * @version 1.0.0
 * @see CircularLinkedSet
 */
@NullUnmarked
public interface CircularSet<E> {

    /**
     * 查看并返回最早加入的元素（不移除）。
     *
     * @return 最早加入的元素
     * @throws java.util.NoSuchElementException 如果集合为空
     */
    E peekEldest();

    /**
     * 返回集合的固定容量（最大可容纳元素数量）。
     *
     * @return 集合容量
     */
    int capacity();
}
