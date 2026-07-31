package com.chua.common.support.collection;

import java.util.*;
import java.util.function.UnaryOperator;

/**
 * 基于数组的环状数组实现，固定容量，支持环状旋转。
 * <p>
 * 内部使用数组存储元素，通过头部索引（{@code head}）实现环状访问。
 * 当元素数量达到容量上限后，继续添加元素会覆盖最旧的元素。
 * </p>
 *
 * <h3>适用场景</h3>
 * <ul>
 *   <li><strong>日志/事件环形缓冲区</strong> — 固定保留最近 N 条日志，自动丢弃最旧记录，适合嵌入服务本地缓存</li>
 *   <li><strong>滑动窗口</strong> — 统计最近 N 次操作/请求的聚合指标，新进旧出</li>
 *   <li><strong>轮询/负载均衡</strong> — 结合 {@link #rotate()} / {@link #rotate(int)} 轮流取出元素，实现简单轮询</li>
 *   <li><strong>固定长度轨迹</strong> — 记录最近操作轨迹、最近 N 个输入参数</li>
 * </ul>
 *
 * <p>
 * 线程不安全，多线程环境请自行加锁或使用 {@link Collections#synchronizedList(List)} 包装。
 * </p>
 *
 * @param <E> 元素类型
 * @author CH
 * @version 1.0.0
 * @see CircularArray
 */
public class CircularArrayList<E> implements CircularArray<E> {

    /**
     * 内部存储数组
     */
    private Object[] elements;

    /**
     * 头部索引，指向逻辑上的第一个元素
     */
    private int head;

    /**
     * 当前元素数量
     */
    private int size;

    /**
     * 数组固定容量
     */
    private int capacity;

    /**
     * 使用默认容量创建环状数组。
     *
     * @param <E>       元素类型
     * @param capacity  数组容量，必须大于 0
     * @return 环状数组实例
     * @throws IllegalArgumentException 如果 capacity 小于等于 0
     */
    public static <E> CircularArrayList<E> of(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须大于 0");
        }
        return new CircularArrayList<>(capacity);
    }

    /**
     * 使用指定容量和初始集合创建环状数组。
     * <p>
     * 如果集合大小超过容量，只有最后 {@code capacity} 个元素会被保留。
     * </p>
     *
     * @param capacity  数组容量，必须大于 0
     * @param c         初始集合
     * @param <E>       元素类型
     * @return 环状数组实例
     * @throws IllegalArgumentException 如果 capacity 小于等于 0
     */
    public static <E> CircularArrayList<E> of(int capacity, Collection<? extends E> c) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须大于 0");
        }
        CircularArrayList<E> list = new CircularArrayList<>(capacity);
        if (c != null) {
            for (E e : c) {
                list.add(e);
            }
        }
        return list;
    }

    /**
     * 构造方法。
     *
     * @param capacity 数组容量
     */
    public CircularArrayList(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须大于 0");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
        this.head = 0;
        this.size = 0;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean contains(Object o) {
        for (int i = 0; i < size; i++) {
            if (Objects.equals(get(i), o)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Iterator<E> iterator() {
        return new Iterator<E>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < size;
            }

            @Override
            public E next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return get(index++);
            }

            @Override
            public void remove() {
                if (index == 0) {
                    throw new IllegalStateException();
                }
                CircularArrayList.this.remove(index - 1);
                index--;
            }
        };
    }

    @Override
    public Object[] toArray() {
        Object[] result = new Object[size];
        for (int i = 0; i < size; i++) {
            result[i] = get(i);
        }
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        T[] result = a.length >= size ? a : (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), size);
        for (int i = 0; i < size; i++) {
            result[i] = (T) get(i);
        }
        if (a.length > size) {
            result[size] = null;
        }
        return result;
    }

    @Override
    public boolean add(E e) {
        if (size < capacity) {
            int index = (head + size) % capacity;
            elements[index] = e;
            size++;
        } else {
            elements[head] = e;
            head = (head + 1) % capacity;
        }
        return true;
    }

    @Override
    public boolean remove(Object o) {
        for (int i = 0; i < size; i++) {
            if (Objects.equals(get(i), o)) {
                remove(i);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object o : c) {
            if (!contains(o)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        boolean modified = false;
        for (E e : c) {
            modified |= add(e);
        }
        return modified;
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        checkPositionIndex(index);
        boolean modified = false;
        for (E e : c) {
            add(index, e);
            modified = true;
            index++;
        }
        return modified;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean modified = false;
        for (int i = size - 1; i >= 0; i--) {
            if (c.contains(get(i))) {
                remove(i);
                modified = true;
            }
        }
        return modified;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        boolean modified = false;
        for (int i = size - 1; i >= 0; i--) {
            if (!c.contains(get(i))) {
                remove(i);
                modified = true;
            }
        }
        return modified;
    }

    @Override
    public void clear() {
        for (int i = 0; i < size; i++) {
            elements[(head + i) % capacity] = null;
        }
        head = 0;
        size = 0;
    }

    @Override
    @SuppressWarnings("unchecked")
    public E get(int index) {
        checkElementIndex(index);
        return (E) elements[actualIndex(index)];
    }

    @Override
    public E set(int index, E element) {
        checkElementIndex(index);
        E old = get(index);
        elements[actualIndex(index)] = element;
        return old;
    }

    @Override
    public void add(int index, E element) {
        checkPositionIndex(index);
        if (size == capacity) {
            if (index == 0) {
                elements[head] = element;
                head = (head + 1) % capacity;
            } else if (index == size) {
                add(element);
            } else {
                remove(size - 1);
                for (int i = size - 1; i >= index; i--) {
                    set(i + 1, get(i));
                }
                set(index, element);
            }
        } else {
            if (index == size) {
                add(element);
            } else {
                for (int i = size; i > index; i--) {
                    set(i, get(i - 1));
                }
                set(index, element);
                size++;
            }
        }
    }

    @Override
    public E remove(int index) {
        checkElementIndex(index);
        E old = get(index);
        for (int i = index; i < size - 1; i++) {
            set(i, get(i + 1));
        }
        int lastIndex = (head + size - 1) % capacity;
        elements[lastIndex] = null;
        size--;
        return old;
    }

    @Override
    public int indexOf(Object o) {
        for (int i = 0; i < size; i++) {
            if (Objects.equals(get(i), o)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        for (int i = size - 1; i >= 0; i--) {
            if (Objects.equals(get(i), o)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public ListIterator<E> listIterator() {
        return listIterator(0);
    }

    @Override
    public ListIterator<E> listIterator(int index) {
        checkPositionIndex(index);
        return new ListIterator<E>() {
            private int currentIndex = index;
            private int lastIndex = -1;

            @Override
            public boolean hasNext() {
                return currentIndex < size;
            }

            @Override
            public E next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                lastIndex = currentIndex;
                return get(currentIndex++);
            }

            @Override
            public boolean hasPrevious() {
                return currentIndex > 0;
            }

            @Override
            public E previous() {
                if (!hasPrevious()) {
                    throw new NoSuchElementException();
                }
                lastIndex = --currentIndex;
                return get(currentIndex);
            }

            @Override
            public int nextIndex() {
                return currentIndex;
            }

            @Override
            public int previousIndex() {
                return currentIndex - 1;
            }

            @Override
            public void remove() {
                if (lastIndex < 0) {
                    throw new IllegalStateException();
                }
                CircularArrayList.this.remove(lastIndex);
                currentIndex = lastIndex;
                lastIndex = -1;
            }

            @Override
            public void set(E e) {
                if (lastIndex < 0) {
                    throw new IllegalStateException();
                }
                CircularArrayList.this.set(lastIndex, e);
            }

            @Override
            public void add(E e) {
                CircularArrayList.this.add(currentIndex, e);
                currentIndex++;
                lastIndex = -1;
            }
        };
    }

    @Override
    public List<E> subList(int fromIndex, int toIndex) {
        throw new UnsupportedOperationException("环状数组不支持子列表视图");
    }

    @Override
    public void replaceAll(UnaryOperator<E> operator) {
        for (int i = 0; i < size; i++) {
            set(i, operator.apply(get(i)));
        }
    }

    @Override
    public void sort(Comparator<? super E> c) {
        List<E> sorted = new ArrayList<>(this);
        sorted.sort(c);
        clear();
        for (E e : sorted) {
            add(e);
        }
    }

    // ==================== CircularArray 接口方法 ====================

    @Override
    public E peek() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        return get(0);
    }

    @Override
    public E poll() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        return remove(0);
    }

    @Override
    public void rotate() {
        rotate(1);
    }

    @Override
    public void rotate(int distance) {
        if (isEmpty()) {
            if (distance != 0) {
                throw new IllegalArgumentException("空数组无法旋转");
            }
            return;
        }
        int n = ((distance % size) + size) % size;
        if (n > 0) {
            head = (head + n) % capacity;
        }
    }

    @Override
    public int capacity() {
        return capacity;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 将逻辑索引转换为实际数组索引。
     *
     * @param index 逻辑索引
     * @return 实际数组索引
     */
    private int actualIndex(int index) {
        return (head + index) % capacity;
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
        ListIterator<E> it1 = listIterator();
        ListIterator<?> it2 = other.listIterator();
        while (it1.hasNext() && it2.hasNext()) {
            E e1 = it1.next();
            Object e2 = it2.next();
            if (!Objects.equals(e1, e2)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < size; i++) {
            E e = get(i);
            result = 31 * result + (e == null ? 0 : e.hashCode());
        }
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(get(i));
        }
        sb.append(']');
        return sb.toString();
    }
}
