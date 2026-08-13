package com.chua.common.support.collection;

import java.util.Collection;

/**
 * 数据存储接口，抽象堆内（On-Heap）和堆外（Off-Heap）两种存储策略。
 *
 * <p>为 {@link LazyExpiringList} 提供统一的存储抽象，使其无需关心
 * 底层是 JVM 堆内存还是 native 内存。</p>
 *
 * @param <E> 元素类型
 * @author CH
 * @version 1.0.0
 * @see OnHeapDataStore
 * @see OffHeapDataStore
 * @see LazyExpiringList
 */
public interface DataStore<E> extends AutoCloseable {

    int append(E element);

    default int appendAll(Collection<? extends E> elements) {
        int count = 0;
        for (E e : elements) {
            append(e);
            count++;
        }
        return count;
    }

    E get(int index);

    int size();

    boolean isEmpty();

    void clear();

    @Override
    void close();

    boolean isReadOnly();

    boolean isOffHeap();

    long getOffHeapBytes();
}