package com.chua.common.support.collection;

import java.util.Collection;

/**
 * 数据存储接口，抽象堆内（On-Heap）和堆外（Off-Heap）两种存储策略。
 *
 * <p>为 {@link LazyExpiringList} 提供统一的存储抽象，使其无需关心
 * 底层是 JVM 堆内存还是 native 内存。</p>
 *
 * <h3>实现</h3>
 * <ul>
 *   <li>{@link OnHeapDataStore} — 基于 {@link java.util.ArrayList} 的堆内存储</li>
 *   <li>{@link OffHeapDataStore} — 基于 {@link java.lang.foreign.Arena} + {@link java.lang.foreign.MemorySegment} 的堆外存储</li>
 * </ul>
 *
 * @param <E> 元素类型
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 * @see OnHeapDataStore
 * @see OffHeapDataStore
 * @see LazyExpiringList
 */
public interface DataStore<E> extends AutoCloseable {

    /**
     * 追加单个元素到存储末尾。
     *
     * @param element 要追加的元素
     * @return 元素在存储中的索引位置
     * @throws IllegalStateException 如果存储已关闭
     */
    int append(E element);

    /**
     * 批量追加多个元素到存储末尾。
     *
     * <p>默认实现逐个调用 {@link #append(Object)}，实现类可覆盖此方法以提升批量写入性能。</p>
     *
     * @param elements 要追加的元素集合
     * @return 实际追加的元素数量
     * @throws IllegalStateException 如果存储已关闭
     */
    default int appendAll(Collection<? extends E> elements) {
        int count = 0;
        for (E e : elements) {
            append(e);
            count++;
        }
        return count;
    }

    /**
     * 获取指定索引位置的元素。
     *
     * @param index 元素索引（从 0 开始）
     * @return 索引位置的元素
     * @throws IndexOutOfBoundsException 如果索引越界
     * @throws IllegalStateException 如果存储已关闭
     */
    E get(int index);

    /**
     * 获取存储中的元素数量。
     *
     * @return 元素数量
     */
    int size();

    /**
     * 判断存储是否为空。
     *
     * @return 如果没有任何元素返回 {@code true}
     */
    boolean isEmpty();

    /**
     * 清空存储中的所有元素并释放相关资源。
     *
     * <p>堆外模式下会释放已分配的 native 内存，堆内模式下仅清空列表。</p>
     *
     * @throws IllegalStateException 如果存储已关闭
     */
    void clear();

    /**
     * 关闭存储并释放所有资源。
     *
     * <p>关闭后任何访问操作将抛出 {@link IllegalStateException}。</p>
     * <p>堆外模式下将调用 {@link java.lang.foreign.Arena#close()} 确定性释放 native 内存。</p>
     */
    @Override
    void close();

    /**
     * 判断存储是否为只读模式。
     *
     * @return 如果只读返回 {@code true}
     */
    boolean isReadOnly();

    /**
     * 判断存储是否使用堆外内存。
     *
     * @return 堆外存储返回 {@code true}，堆内存储返回 {@code false}
     */
    boolean isOffHeap();

    /**
     * 获取堆外内存占用字节数。
     *
     * <p>仅堆外存储模式返回实际占用字节数，堆内模式始终返回 0。</p>
     *
     * @return 堆外内存字节数
     */
    long getOffHeapBytes();
}
