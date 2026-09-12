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
* 基于 {@code int[]} 的整数列表，避免 {@link Integer} 装箱开销。
* <p>
* 内部使用基本类型 {@code int[]} 存储数据，提供 {@link List}{@code <Integer>} 语义。
* </p>
*
* <h3>适用场景</h3>
* <ul>
*   <li><strong>高性能整数序列</strong> — 避免 {@code Integer} 装箱/拆箱，降低 GC 压力</li>
*   <li><strong>数值统计/滑动窗口</strong> — 存储最近 N 个 int 值，配合聚合计算</li>
*   <li><strong>协议字段数组</strong> — 二进制协议解析后的 int 数组</li>
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
* @see LongList
 */
public class IntList extends AbstractList<Integer> implements RandomAccess {

    /**
    * 内部 int 数组
     */
    private int[] elements;

    /**
    * 当前元素数量
     */
    private int size;

    /**
    * 默认初始容量
     */
    private static final int DEFAULT_CAPACITY = 10;

    /**
    * 创建空整数列表。
    *
    * @return IntList 实例
     */
    public static IntList of() {
        return new IntList();
    }

    /**
    * 使用指定初始容量创建整数列表。
    *
    * @param capacity 初始容量，必须大于等于 0
    * @return IntList 实例
     */
    public static IntList of(int capacity) {
        return new IntList(capacity);
    }

    /**
    * 从基本类型 int 数组创建整数列表。
    *
    * @param values int 数组
    * @return IntList 实例
     */
    public static IntList of(int[] values) {
        IntList list = new IntList(values.length);
        list.addAll(values);
        return list;
    }

    /**
    * 从 {@link Collection}{@code <Integer>} 创建整数列表。
    *
    * @param values 整数集合
    * @return IntList 实例
     */
    public static IntList of(Collection<Integer> values) {
        IntList list = new IntList(values.size());
        list.addAll(values);
        return list;
    }

    /**
    * 构造方法，创建默认容量的整数列表。
     */
    public IntList() {
        this(DEFAULT_CAPACITY);
    }

    /**
    * 构造方法，创建指定初始容量的整数列表。
    *
    * @param capacity 初始容量
     */
    public IntList(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("容量不能为负数: " + capacity);
        }
        this.elements = new int[capacity];
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
        if (!(o instanceof Integer)) {
            return false;
        }
        int value = (Integer) o;
        for (int i = 0; i < size; i++) {
            if (elements[i] == value) {
                return true;
            }
        }
        return false;
    }

    @Override
    /** 获取 */
    public Integer get(int index) {
        checkElementIndex(index);
        return elements[index];
    }

    @Override
    /** 设置 */
    public Integer set(int index, Integer element) {
        checkElementIndex(index);
        int old = elements[index];
        elements[index] = element;
        return old;
    }

    @Override
    /** 添加 */
    public void add(int index, Integer element) {
        checkPositionIndex(index);
        ensureCapacity(size + 1);
        System.arraycopy(elements, index, elements, index + 1, size - index);
        elements[index] = element;
        size++;
    }

    @Override
    /** 添加 */
    public boolean add(Integer element) {
        ensureCapacity(size + 1);
        elements[size++] = element;
        return true;
    }

    @Override
    /** 添加All */
    public boolean addAll(Collection<? extends Integer> c) {
        ensureCapacity(size + c.size());
        for (Integer i : c) {
            elements[size++] = i;
        }
        return !c.isEmpty();
    }

    @Override
    /** 添加All */
    public boolean addAll(int index, Collection<? extends Integer> c) {
        checkPositionIndex(index);
        ensureCapacity(size + c.size());
        int moveCount = size - index;
        if (moveCount > 0) {
            System.arraycopy(elements, index, elements, index + c.size(), moveCount);
        }
        int srcIndex = 0;
        for (Integer i : c) {
            elements[index + srcIndex++] = i;
        }
        size += c.size();
        return !c.isEmpty();
    }

    @Override
    /** 移除 */
    public Integer remove(int index) {
        checkElementIndex(index);
        int old = elements[index];
        System.arraycopy(elements, index + 1, elements, index, size - index - 1);
        elements[--size] = 0;
        return old;
    }

    @Override
    /** 移除 */
    public boolean remove(Object o) {
        if (!(o instanceof Integer)) {
            return false;
        }
        int value = (Integer) o;
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
        Arrays.fill(elements, 0, size, 0);
        size = 0;
    }

    @Override
    /** IndexOf */
    public int indexOf(Object o) {
        if (!(o instanceof Integer)) {
            return -1;
        }
        int value = (Integer) o;
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
        if (!(o instanceof Integer)) {
            return -1;
        }
        int value = (Integer) o;
        for (int i = size - 1; i >= 0; i--) {
            if (elements[i] == value) {
                return i;
            }
        }
        return -1;
    }

    @Override
    /** Iterator */
    public Iterator<Integer> iterator() {
        return new Iterator<Integer>() {
            /** 索引位置 */
            private int index = 0;

            @Override
            /** 是否拥有Next */
            public boolean hasNext() {
                return index < size;
            }

            @Override
            /** Next */
            public Integer next() {
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
                IntList.this.remove(index - 1);
                index--;
            }
        };
    }

    @Override
    /** SubList */
    public List<Integer> subList(int fromIndex, int toIndex) {
        throw new UnsupportedOperationException("整数列表不支持子列表视图");
    }

    // ==================== 数值专用 API ====================

    /**
    * 返回指定位置的 int 值（避免自动装箱）。
    *
    * @param index 索引
    * @return int 值
     */
    public int getInt(int index) {
        checkElementIndex(index);
        return elements[index];
    }

    /**
    * 设置指定位置的 int 值（避免自动装箱）。
    *
    * @param index 索引
    * @param value int 值
     */
    public void setInt(int index, int value) {
        checkElementIndex(index);
        elements[index] = value;
    }

    /**
    * 追加一个 int 值（避免自动装箱）。
    *
    * @param value int 值
     */
    public void addInt(int value) {
        ensureCapacity(size + 1);
        elements[size++] = value;
    }

    /**
    * 批量追加 int 值（避免自动装箱）。
    *
    * @param values int 数组
     */
    public void addAll(int[] values) {
        ensureCapacity(size + values.length);
        System.arraycopy(values, 0, elements, size, values.length);
        size += values.length;
    }

    /**
    * 计算所有元素的和。
    *
    * @return 元素总和
     */
    public int sum() {
        int sum = 0;
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
    public int max() {
        if (size == 0) {
            throw new NoSuchElementException();
        }
        int max = elements[0];
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
    public int min() {
        if (size == 0) {
            throw new NoSuchElementException();
        }
        int min = elements[0];
        for (int i = 1; i < size; i++) {
            if (elements[i] < min) {
                min = elements[i];
            }
        }
        return min;
    }

    /**
    * 将列表转换为基本类型 int 数组。
    *
    * @return int 数组
     */
    public int[] toIntArray() {
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
        ListIterator<Integer> it1 = listIterator();
        ListIterator<?> it2 = other.listIterator();
        while (it1.hasNext() && it2.hasNext()) {
            Integer e1 = it1.next();
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
            result = 31 * result + elements[i];
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
