package com.chua.common.support.collection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 堆内数据存储，基于 {@link ArrayList} 实现。
 *
 * @param <E> 元素类型
 * @author CH
 * @version 1.0.0
 * @see DataStore
 * @see OffHeapDataStore
 */
public class OnHeapDataStore<E> implements DataStore<E> {

    private final List<E> elements;
    private volatile boolean closed;

    public OnHeapDataStore() {
        this.elements = new ArrayList<>();
    }

    public OnHeapDataStore(List<E> data) {
        this.elements = new ArrayList<>(data);
    }

    @Override
    public int append(E element) {
        ensureOpen();
        elements.add(element);
        return elements.size() - 1;
    }

    @Override
    public int appendAll(Collection<? extends E> elements) {
        ensureOpen();
        this.elements.addAll(elements);
        return elements.size();
    }

    @Override
    public E get(int index) {
        ensureOpen();
        return elements.get(index);
    }

    @Override
    public int size() {
        return elements.size();
    }

    @Override
    public boolean isEmpty() {
        return elements.isEmpty();
    }

    @Override
    public void clear() {
        ensureOpen();
        elements.clear();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            elements.clear();
        }
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean isOffHeap() {
        return false;
    }

    @Override
    public long getOffHeapBytes() {
        return 0;
    }

    public List<E> toList() {
        return new ArrayList<>(elements);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("OnHeapDataStore 已关闭，不可再访问");
        }
    }
}