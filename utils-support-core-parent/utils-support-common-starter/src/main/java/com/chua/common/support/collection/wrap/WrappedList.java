package com.chua.common.support.collection.wrap;

import java.io.Serializable;
import java.util.AbstractList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.RandomAccess;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * 列表分装对象：内部持有可切换的列表存储（默认 Eclipse {@code FastList}，
 * classpath 缺失时为 {@code ArrayList}），对外表现为标准 {@link List}。
 *
 * <p>本类继承 {@link AbstractList}，{@code equals} / {@code hashCode} / {@code toString}
 * 按 {@code List} 契约基于元素内容计算，与 JDK / Eclipse 存储互为等值；
 * 高频方法（随机访问、增删改、迭代、流）全部直接委托底层存储，仅一层虚调用。</p>
 *
 * <p>声明 {@link RandomAccess}：两个内置后端返回的存储（FastList / ArrayList）
 * 均实现该接口，因此分装对象同样具备随机访问特征，二分查找等算法不会退化为顺序遍历。</p>
 *
 * @param <E> 元素类型
 * @author CH
 * @since 4.0.0.42
 * @see Wraps#list()
 */
public class WrappedList<E> extends AbstractList<E> implements RandomAccess, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 底层存储（eclipse FastList 或 JDK ArrayList）。
     */
    private final List<E> delegate;

    /**
     * 以指定列表为存储进行分装。
     *
     * @param delegate 底层存储，不可为 {@code null}
     */
    public WrappedList(List<E> delegate) {
        if (delegate == null) {
            throw new NullPointerException("delegate");
        }
        this.delegate = delegate;
    }

    /**
     * 取出底层存储（用于需要原始类型（如 {@code FastList}）的互操作场景）。
     *
     * @return 底层存储
     */
    public List<E> delegate() {
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
    public E get(int index) {
        return delegate.get(index);
    }

    @Override
    public E set(int index, E element) {
        return delegate.set(index, element);
    }

    @Override
    public boolean add(E e) {
        return delegate.add(e);
    }

    @Override
    public void add(int index, E element) {
        delegate.add(index, element);
    }

    @Override
    public E remove(int index) {
        return delegate.remove(index);
    }

    @Override
    public boolean remove(Object o) {
        return delegate.remove(o);
    }

    @Override
    public int indexOf(Object o) {
        return delegate.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return delegate.lastIndexOf(o);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        return delegate.addAll(c);
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        return delegate.addAll(index, c);
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
    public Iterator<E> iterator() {
        return delegate.iterator();
    }

    @Override
    public ListIterator<E> listIterator() {
        return delegate.listIterator();
    }

    @Override
    public ListIterator<E> listIterator(int index) {
        return delegate.listIterator(index);
    }

    @Override
    public List<E> subList(int fromIndex, int toIndex) {
        return new WrappedList<>(delegate.subList(fromIndex, toIndex));
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
    public void sort(Comparator<? super E> c) {
        delegate.sort(c);
    }

    @Override
    public void replaceAll(UnaryOperator<E> operator) {
        delegate.replaceAll(operator);
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
}
