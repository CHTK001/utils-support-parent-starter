package com.chua.common.support.collection.wrap;

import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NavigableSet;
import java.util.SortedSet;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 有序集合分装对象：内部持有可切换的有序集合存储，对外表现为标准 {@link SortedSet}。
 *
 * <p><b>存储说明：</b>Eclipse Collections 的 TreeSortedSet 内部即 JDK TreeSet 薄壳
 * （已核验其字段就是 treeSet），再包一层只会多耗内存，因此两个内置后端均直接返回
 * {@code TreeSet} 存储——本类型同样以统一入口（{@link Wraps#sortedSet()}）与
 * 后端切换点为价值，而非内存收益。需要导航操作（{@code headSet} 之外的
 * floor/ceiling 等）时请使用 {@link Wraps#navigableSet()}。</p>
 *
 * <p>本类继承 {@link AbstractSet} 实现 {@link SortedSet}，{@code equals} /
 * {@code hashCode} 按 {@code Set} 契约基于内容计算；区间视图
 * （headSet/tailSet/subSet）同样返回分装对象并共享底层存储。</p>
 *
 * @param <E> 元素类型
 * @author CH
 * @since 4.0.0.42
 * @see Wraps#sortedSet()
 * @see NavigableSet
 */
public class WrappedSortedSet<E> extends AbstractSet<E> implements SortedSet<E>, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 底层存储（两后端均为 JDK TreeSet）。
     */
    private final SortedSet<E> delegate;

    /**
     * 以指定有序集合为存储进行分装。
     *
     * @param delegate 底层存储，不可为 {@code null}
     */
    public WrappedSortedSet(SortedSet<E> delegate) {
        if (delegate == null) {
            throw new NullPointerException("delegate");
        }
        this.delegate = delegate;
    }

    /**
     * 取出底层存储。
     *
     * @return 底层存储
     */
    public SortedSet<E> delegate() {
        return delegate;
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return delegate.contains(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return delegate.containsAll(c);
    }

    @Override
    public boolean add(E e) {
        return delegate.add(e);
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        return delegate.addAll(c);
    }

    @Override
    public boolean remove(Object o) {
        return delegate.remove(o);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return delegate.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return delegate.retainAll(c);
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        return delegate.removeIf(filter);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public Iterator<E> iterator() {
        return delegate.iterator();
    }

    @Override
    public Object[] toArray() {
        return delegate.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return delegate.toArray(a);
    }

    @Override
    public <T> T[] toArray(IntFunction<T[]> generator) {
        return delegate.toArray(generator);
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        delegate.forEach(action);
    }

    @Override
    public Spliterator<E> spliterator() {
        return delegate.spliterator();
    }

    @Override
    public Stream<E> stream() {
        return delegate.stream();
    }

    @Override
    public Stream<E> parallelStream() {
        return delegate.parallelStream();
    }

    // ============================== SortedSet ===============================

    @Override
    public Comparator<? super E> comparator() {
        return delegate.comparator();
    }

    @Override
    public E first() {
        return delegate.first();
    }

    @Override
    public E last() {
        return delegate.last();
    }

    @Override
    public SortedSet<E> headSet(E toElement) {
        return new WrappedSortedSet<>(delegate.headSet(toElement));
    }

    @Override
    public SortedSet<E> tailSet(E fromElement) {
        return new WrappedSortedSet<>(delegate.tailSet(fromElement));
    }

    @Override
    public SortedSet<E> subSet(E fromElement, E toElement) {
        return new WrappedSortedSet<>(delegate.subSet(fromElement, toElement));
    }
}
