package com.chua.common.support.collection;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 排序列表接口，继承 {@link List}。
 * <p>
 * 在 {@link List} 基础上提供获取有序列表首尾元素的功能，
 * 实现类 {@link SortedArrayList} 会在添加元素时自动按比较器将元素插入到正确位置，以保持列表始终有序。
 * </p>
 *
 * @param <E> 元素类型
 * @author CH
 * @version 1.0.0
 */
public interface SortedList<E> extends List<E> {

    /**
     * 获取列表中的第一个元素（最小值）。
     *
     * @return 第一个元素
     * @throws NoSuchElementException 如果列表为空
     */
    E first();

    /**
     * 获取列表中的最后一个元素（最大值）。
     *
     * @return 最后一个元素
     * @throws NoSuchElementException 如果列表为空
     */
    E last();

    /**
     * 返回一个空的不可变排序列表实例。
     *
     * @param <E> 元素类型
     * @return 空的排序列表
     */
    static <E> SortedList<E> emptyList() {
        return new SortedArrayList<E>(Comparator.comparingInt(Object::hashCode));
    }
}
