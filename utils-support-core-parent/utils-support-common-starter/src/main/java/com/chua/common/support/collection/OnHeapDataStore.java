package com.chua.common.support.collection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
* 堆内数据存储，基于 {@link ArrayList} 实现。
*
* <p>所有元素存储在 JVM 堆内存中，适用于数据量较小或对内存控制无特殊要求的场景。
* 关闭后清空内部列表，释放堆内引用。</p>
*
* <h3>线程安全</h3>
* <p>本类非线程安全，依赖 {@link LazyExpiringList} 的外部同步保护。</p>
*
* @param <E> 元素类型
* @author CH
* @since 4.0.0.42
* @version 1.0.0
* @see DataStore
* @see OffHeapDataStore
* @see LazyExpiringList
 */
public class OnHeapDataStore<E> implements DataStore<E> {

    /** 内部元素列表，存储所有堆内数据 */
    private final List<E> elements;

    /** 关闭标志，volatile 保证可见性 */
    private volatile boolean closed;

    /**
    * 构造空的堆内存储。
     */
    public OnHeapDataStore() {
        this.elements = new ArrayList<>();
    }

    /**
    * 从已有数据构造堆内存储。
    *
    * @param data 初始数据列表（会创建防御性拷贝）
     */
    public OnHeapDataStore(List<E> data) {
        this.elements = new ArrayList<>(data);
    }

    /**
    * {@inheritDoc}
    *
    * <p>追加元素到列表末尾，返回其索引位置。</p>
     */
    @Override
    public int append(E element) {
        ensureOpen();
        elements.add(element);
        return elements.size() - 1;
    }

    /**
    * {@inheritDoc}
    *
    * <p>批量追加所有元素，使用 {@link ArrayList#addAll(Collection)} 一次性添加。</p>
     */
    @Override
    public int appendAll(Collection<? extends E> elements) {
        ensureOpen();
        this.elements.addAll(elements);
        return elements.size();
    }

    /**
    * {@inheritDoc}
     */
    @Override
    public E get(int index) {
        ensureOpen();
        return elements.get(index);
    }

    /**
    * {@inheritDoc}
     */
    @Override
    public int size() {
        return elements.size();
    }

    /**
    * {@inheritDoc}
     */
    @Override
    public boolean isEmpty() {
        return elements.isEmpty();
    }

    /**
    * {@inheritDoc}
    *
    * <p>清空内部列表，释放堆内引用。</p>
     */
    @Override
    public void clear() {
        ensureOpen();
        elements.clear();
    }

    /**
    * {@inheritDoc}
    *
    * <p>关闭后清空内部列表，后续任何访问将抛出 {@link IllegalStateException}。</p>
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            elements.clear();
        }
    }

    /**
    * {@inheritDoc}
    *
    * <p>堆内存储始终返回 {@code false}。</p>
     */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /**
    * {@inheritDoc}
    *
    * <p>堆内存储始终返回 {@code false}。</p>
     */
    @Override
    public boolean isOffHeap() {
        return false;
    }

    /**
    * {@inheritDoc}
    *
    * <p>堆内存储始终返回 0。</p>
     */
    @Override
    public long getOffHeapBytes() {
        return 0;
    }

    /**
    * 将存储内容导出为新的 {@link ArrayList}（防御性拷贝）。
    *
    * @return 包含所有元素的列表副本
     */
    public List<E> toList() {
        return new ArrayList<>(elements);
    }

    /**
    * 检查存储是否已关闭，若已关闭则抛出异常。
    *
    * @throws IllegalStateException 如果存储已关闭
     */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("OnHeapDataStore 已关闭，不可再访问");
        }
    }
}
