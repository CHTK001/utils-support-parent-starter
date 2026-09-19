package com.chua.common.support.collection;

import java.util.*;

/**
 * 基于 ArrayList 的排序列表实现，在添加元素时自动按比较器排序。
 * <p>
 * 通过 {@link Comparator} 或元素的 {@link Comparable} 自然顺序来确定元素位置。
 * 使用二分查找（{@link Collections#binarySearch(List, Object, Comparator)}）
 * 快速定位插入点，以保持列表始终处于有序状态。
 * </p>
 *
 * @param <E> 元素类型
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 */
public class SortedArrayList<E> extends ArrayList<E> implements SortedList<E> {

    private final Comparator<? super E> comparator;

    /**
     * 使用指定比较器创建排序列表。
     *
     * @param comparator 用于排序的比较器
     */
    public SortedArrayList(Comparator<? super E> comparator) {
        this.comparator = comparator;
    }

    /**
     * 使用指定比较器和初始容量创建排序列表。
     *
     * @param comparator      用于排序的比较器
     * @param initialCapacity 初始容量
     */
    public SortedArrayList(Comparator<? super E> comparator, int initialCapacity) {
        super(initialCapacity);
        this.comparator = comparator;
    }

    /**
     * 使用指定比较器和初始集合创建排序列表，创建后立即按比较器排序。
     *
     * @param comparator 用于排序的比较器
     * @param c          初始集合，其中的元素会被排序
     */
    public SortedArrayList(Comparator<? super E> comparator, Collection<? extends E> c) {
        super(c);
        this.comparator = comparator;
        sort(comparator);
    }

    /**
     * 获取有序列表中的第一个元素（最小值）。
     *
     * @return 第一个元素
     * @throws NoSuchElementException 如果列表为空
     */
    @Override
    public E first() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        return get(0);
    }

    /**
     * 获取有序列表中的最后一个元素（最大值）。
     *
     * @return 最后一个元素
     * @throws NoSuchElementException 如果列表为空
     */
    @Override
    public E last() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        return get(size() - 1);
    }

    /**
     * 向有序列表中添加一个元素，通过二分查找自动插入到正确位置以保持顺序。
     *
     * @param e 要添加的元素
     * @return true
     */
    @Override
    public boolean add(E e) {
        int index = findInsertionIndex(e);
        super.add(index, e);
        return true;
    }

    /**
     * 向有序列表中添加一个元素（忽略索引参数，自动按排序规则插入到正确位置）。
     *
     * @param index   忽略，实际插入位置由排序决定
     * @param element 要添加的元素
     */
    @Override
    public void add(int index, E element) {
        int insertIndex = findInsertionIndex(element);
        super.add(insertIndex, element);
    }

    /**
     * 批量添加元素，每个元素按排序规则自动插入到正确位置。
     *
     * @param c 要添加的集合
     * @return 如果集合非空返回 true，否则返回 false
     */
    @Override
    public boolean addAll(Collection<? extends E> c) {
        for (E e : c) {
            add(e);
        }
        return !c.isEmpty();
    }

    /**
     * 批量添加元素（忽略索引参数，每个元素自动按排序规则插入）。
     *
     * @param index 忽略
     * @param c     要添加的集合
     * @return 如果集合非空返回 true，否则返回 false
     */
    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        for (E e : c) {
            add(e);
        }
        return !c.isEmpty();
    }

    /**
     * 替换指定索引位置的元素（先删除旧元素，再按排序规则重新插入新元素）。
     *
     * @param index   要替换的位置索引
     * @param element 新元素
     * @return 被替换的旧元素
     */
    @Override
    public E set(int index, E element) {
        E old = remove(index);
        add(element);
        return old;
    }

    /**
     * 使用指定比较器重新排序列表（覆盖 {@link ArrayList#sort}）。
     *
     * @param c 用于排序的比较器
     */
    @Override
    public void sort(Comparator<? super E> c) {
        super.sort(c);
    }

    /**
     * 使用二分查找确定元素在有序列表中的插入位置。
     * <p>
     * 如果存在比较器，则使用比较器进行二分查找；
     * 否则要求元素实现 {@link Comparable} 接口，使用自然顺序进行查找。
     * </p>
     *
     * @param e 要插入的元素
     * @return 插入位置的索引（始终为非负数）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int findInsertionIndex(E e) {
        if (comparator != null) {
            int index = Collections.binarySearch(this, e, comparator);
            return index >= 0 ? index : -(index + 1);
        }
        Comparable<? super E> key = (Comparable<? super E>) e;
        int index = Collections.binarySearch((List) this, key);
        return index >= 0 ? index : -(index + 1);
    }
}
