package com.chua.common.support.collection;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * 基于 {@code long[]} 的长整数列表，避免 {@link Long} 装箱开销。
 * <p>
 * 内部使用基本类型 {@code long[]} 存储数据，提供 {@link List}{@code <Long>} 语义。
 * </p>
 *
 * <h3>适用场景</h3>
 * <ul>
 *   <li><strong>高性能长整数序列</strong> — 避免 {@code Long} 装箱/拆箱，降低 GC 压力</li>
 *   <li><strong>时间戳/ID 序列</strong> — 存储最近 N 个 long 值（时间戳、ID 等）</li>
 *   <li><strong>协议字段数组</strong> — 二进制协议解析后的 long 数组</li>
 *   <li><strong>批量运算</strong> — 求和、均值、最值等批量数值计算</li>
 * </ul>
 *
 * <p>
 * 线程不安全，多线程环境请自行加锁。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 * @see IntList
 */
public class LongList extends AbstractList<Long> implements RandomAccess {

    /**
     * 内部 long 数组
     */
    private long[] elements;

    /**
     * 当前元素数量
     */
    private int size;

    /**
     * 默认初始容量
     */
    private static final int DEFAULT_CAPACITY = 10;

    /**
     * 创建空长整数列表。
     *
     * @return LongList 实例
     */
    public static LongList of() {
        return new LongList();
    }

    /**
     * 使用指定初始容量创建长整数列表。
     *
     * @param capacity 初始容量，必须大于等于 0
     * @return LongList 实例
     */
    public static LongList of(int capacity) {
        return new LongList(capacity);
    }

    /**
     * 从基本类型 long 数组创建长整数列表。
     *
     * @param values long 数组
     * @return LongList 实例
     */
    public static LongList of(long[] values) {
        LongList list = new LongList(values.length);
        list.addAll(values);
        return list;
    }

    /**
     * 从 {@link Collection}{@code <Long>} 创建长整数列表。
     *
     * @param values 长整数集合
     * @return LongList 实例
     */
    public static LongList of(Collection<Long> values) {
        LongList list = new LongList(values.size());
        list.addAll(values);
        return list;
    }

    /**
     * 构造方法，创建默认容量的长整数列表。
     */
    public LongList() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 构造方法，创建指定初始容量的长整数列表。
     *
     * @param capacity 初始容量
     */
    public LongList(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("容量不能为负数: " + capacity);
        }
        this.elements = new long[capacity];
        this.size = 0;
    }

    // ==================== List 接口实现 ====================

    @Override
    /** 获取大小 */
    public int size() {
        return size;
    }

    @Override
    /** 是否Empty */
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    /** Contains */
    public boolean contains(Object o) {
        if (!(o instanceof Long)) {
            return false;
        }
        long value = (Long) o;
        for (int i = 0; i < size; i++) {
            if (elements[i] == value) {
                return true;
            }
        }
        return false;
    }

    @Override
    /** 获取 */
    public Long get(int index) {
        checkElementIndex(index);
        return elements[index];
    }

    @Override
    /** 设置 */
    public Long set(int index, Long element) {
        checkElementIndex(index);
        long old = elements[index];
        elements[index] = element;
        return old;
    }

    @Override
    /** 添加 */
    public void add(int index, Long element) {
        checkPositionIndex(index);
        ensureCapacity(size + 1);
        System.arraycopy(elements, index, elements, index + 1, size - index);
        elements[index] = element;
        size++;
    }

    @Override
    /** 添加 */
    public boolean add(Long element) {
        ensureCapacity(size + 1);
        elements[size++] = element;
        return true;
    }

    @Override
    /** 添加All */
    public boolean addAll(Collection<? extends Long> c) {
        ensureCapacity(size + c.size());
        for (Long l : c) {
            elements[size++] = l;
        }
        return !c.isEmpty();
    }

    @Override
    /** 添加All */
    public boolean addAll(int index, Collection<? extends Long> c) {
        checkPositionIndex(index);
        ensureCapacity(size + c.size());
        int moveCount = size - index;
        if (moveCount > 0) {
            System.arraycopy(elements, index, elements, index + c.size(), moveCount);
        }
        int srcIndex = 0;
        for (Long l : c) {
            elements[index + srcIndex++] = l;
        }
        size += c.size();
        return !c.isEmpty();
    }

    @Override
    /** 移除 */
    public Long remove(int index) {
        checkElementIndex(index);
        long old = elements[index];
        System.arraycopy(elements, index + 1, elements, index, size - index - 1);
        elements[--size] = 0L;
        return old;
    }

    @Override
    /** 移除 */
    public boolean remove(Object o) {
        if (!(o instanceof Long)) {
            return false;
        }
        long value = (Long) o;
        for (int i = 0; i < size; i++) {
            if (elements[i] == value) {
                remove(i);
                return true;
            }
        }
        return false;
    }

    @Override
    /** 移除All */
    public boolean removeAll(Collection<?> c) {
        boolean modified = false;
        for (int i = size - 1; i >= 0; i--) {
            if (c.contains(elements[i])) {
                remove(i);
                modified = true;
            }
        }
        return modified;
    }

    @Override
    /** RetainAll */
    public boolean retainAll(Collection<?> c) {
        boolean modified = false;
        for (int i = size - 1; i >= 0; i--) {
            if (!c.contains(elements[i])) {
                remove(i);
                modified = true;
            }
        }
        return modified;
    }

    @Override
    /** Clear */
    public void clear() {
        Arrays.fill(elements, 0, size, 0L);
        size = 0;
    }

    @Override
    /** IndexOf */
    public int indexOf(Object o) {
        if (!(o instanceof Long)) {
            return -1;
        }
        long value = (Long) o;
        for (int i = 0; i < size; i++) {
            if (elements[i] == value) {
                return i;
            }
        }
        return -1;
    }

    @Override
    /** LastIndexOf */
    public int lastIndexOf(Object o) {
        if (!(o instanceof Long)) {
            return -1;
        }
        long value = (Long) o;
        for (int i = size - 1; i >= 0; i--) {
            if (elements[i] == value) {
                return i;
            }
        }
        return -1;
    }

    @Override
    /** Iterator */
    public Iterator<Long> iterator() {
        return new Iterator<Long>() {
            /** 索引位置 */
            private int index = 0;

            @Override
            /** 是否拥有Next */
            public boolean hasNext() {
                return index < size;
            }

            @Override
            /** Next */
            public Long next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return elements[index++];
            }

            @Override
            /** 移除 */
            public void remove() {
                if (index == 0) {
                    throw new IllegalStateException();
                }
                LongList.this.remove(index - 1);
                index--;
            }
        };
    }

    @Override
    /** SubList */
    public List<Long> subList(int fromIndex, int toIndex) {
        throw new UnsupportedOperationException("长整数列表不支持子列表视图");
    }

    // ==================== 数值专用 API ====================

    /**
     * 返回指定位置的 long 值（避免自动装箱）。
     *
     * @param index 索引
     * @return long 值
     */
    public long getLong(int index) {
        checkElementIndex(index);
        return elements[index];
    }

    /**
     * 设置指定位置的 long 值（避免自动装箱）。
     *
     * @param index 索引
     * @param value long 值
     */
    public void setLong(int index, long value) {
        checkElementIndex(index);
        elements[index] = value;
    }

    /**
     * 追加一个 long 值（避免自动装箱）。
     *
     * @param value long 值
     */
    public void addLong(long value) {
        ensureCapacity(size + 1);
        elements[size++] = value;
    }

    /**
     * 批量追加 long 值（避免自动装箱）。
     *
     * @param values long 数组
     */
    public void addAll(long[] values) {
        ensureCapacity(size + values.length);
        System.arraycopy(values, 0, elements, size, values.length);
        size += values.length;
    }

    /**
     * 计算所有元素的和。
     *
     * @return 元素总和
     */
    public long sum() {
        long sum = 0L;
        for (int i = 0; i < size; i++) {
            sum += elements[i];
        }
        return sum;
    }

    /**
     * 计算所有元素的平均值。
     *
     * @return 元素平均值
     */
    public double average() {
        if (size == 0) {
            return 0.0;
        }
        return (double) sum() / size;
    }

    /**
     * 返回最大元素。
     *
     * @return 最大元素
     * @throws NoSuchElementException 如果列表为空
     */
    public long max() {
        if (size == 0) {
            throw new NoSuchElementException();
        }
        long max = elements[0];
        for (int i = 1; i < size; i++) {
            if (elements[i] > max) {
                max = elements[i];
            }
        }
        return max;
    }

    /**
     * 返回最小元素。
     *
     * @return 最小元素
     * @throws NoSuchElementException 如果列表为空
     */
    public long min() {
        if (size == 0) {
            throw new NoSuchElementException();
        }
        long min = elements[0];
        for (int i = 1; i < size; i++) {
            if (elements[i] < min) {
                min = elements[i];
            }
        }
        return min;
    }

    /**
     * 将列表转换为基本类型 long 数组。
     *
     * @return long 数组
     */
    public long[] toLongArray() {
        return Arrays.copyOf(elements, size);
    }

    // ==================== 内部工具 ====================

    /**
     * 确保容量足够。
     *
     * @param minCapacity 最小容量
     */
    private void ensureCapacity(int minCapacity) {
        if (minCapacity > elements.length) {
            int newCapacity = Math.max(elements.length * 2, minCapacity);
            elements = Arrays.copyOf(elements, newCapacity);
        }
    }

    /**
     * 检查元素索引是否合法。
     *
     * @param index 索引
     */
    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("索引: " + index + ", 大小: " + size);
        }
    }

    /**
     * 检查位置索引是否合法。
     *
     * @param index 索引
     */
    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("索引: " + index + ", 大小: " + size);
        }
    }

    @Override
    /** 判断相等 */
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof List<?> other)) {
            return false;
        }
        if (other.size() != size) {
            return false;
        }
        ListIterator<Long> it1 = listIterator();
        ListIterator<?> it2 = other.listIterator();
        while (it1.hasNext() && it2.hasNext()) {
            Long e1 = it1.next();
            Object e2 = it2.next();
            if (!Objects.equals(e1, e2)) {
                return false;
            }
        }
        return true;
    }

    @Override
    /** HashCode */
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < size; i++) {
            long v = elements[i];
            result = 31 * result + (int) (v ^ (v >>> 32));
        }
        return result;
    }

    @Override
    /** ToString */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(elements[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
