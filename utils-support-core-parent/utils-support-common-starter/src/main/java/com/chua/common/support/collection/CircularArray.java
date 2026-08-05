package com.chua.common.support.collection;

import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 环状数组接口，继承 {@link List}。
 * <p>
 * 在 {@link List} 基础上提供环状数组特有的操作：
 * 固定容量、头部元素查看/移除、环状旋转等。
 * 当元素数量超过容量时，最旧的元素会被自动覆盖。
 * </p>
 *
 * <h3>适用场景</h3>
 * <ul>
 *   <li><strong>日志/事件缓冲区</strong> — 固定保留最近 N 条日志，超出自动丢弃最旧记录</li>
 *   <li><strong>滑动窗口统计</strong> — 如最近 60 秒请求量统计，新请求入队、旧请求自动过期</li>
 *   <li><strong>轮询调度</strong> — 结合 {@link #rotate()} 实现请求轮询、节点轮询</li>
 *   <li><strong>环形缓冲区</strong> — 生产者/消费者模型中，固定大小的读写缓冲区</li>
 * </ul>
 *
 * @param <E> 元素类型
 * @author CH
 * @version 1.0.0
 * @see CircularArrayList
 */
public interface CircularArray<E> extends List<E> {

    /**
     * 查看头部元素（不移除）。
     *
     * @return 头部元素
     * @throws NoSuchElementException 如果数组为空
     */
    E peek();

    /**
     * 移除并返回头部元素。
     *
     * @return 头部元素
     * @throws NoSuchElementException 如果数组为空
     */
    E poll();

    /**
     * 将数组环状旋转一步（头部指向下一个元素）。
     * <p>
     * 旋转后，原头部元素变为尾部元素，第二个元素成为新的头部元素。
     * 如果数组为空或只有一个元素，此方法不产生效果。
     * </p>
     */
    void rotate();

    /**
     * 将数组环状旋转指定步数。
     * <p>
     * 正数表示向前旋转（头部向后移动），
     * 负数表示向后旋转（头部向前移动）。
     * 旋转步数会自动对数组大小取模。
     * </p>
     *
     * @param distance 旋转步数
     * @throws IllegalArgumentException 如果数组为空且 distance 不为 0
     */
    void rotate(int distance);

    /**
     * 返回数组的固定容量（最大可容纳元素数量）。
     *
     * @return 数组容量
     */
    int capacity();
}
