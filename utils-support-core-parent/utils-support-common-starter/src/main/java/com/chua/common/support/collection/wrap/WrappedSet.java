package com.chua.common.support.collection.wrap;

import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 集合分装对象：内部持有可切换的集合存储（默认 Eclipse {@code UnifiedSet}，
 * classpath 缺失时为 {@code HashSet}），对外表现为标准 {@link Set}。
 *
 * <p>本类继承 {@link AbstractSet}，{@code equals} / {@code hashCode} / {@code toString}
 * 按 {@code Set} 契约基于元素内容计算，与 JDK / Eclipse 存储互为等值；
 * 高频方法全部直接委托底层存储。UnifiedSet 采用链式桶去 Node 化存储，
 * 较 HashSet 省约 46% 内存（8 条目小集合场景约 71%），是分装体系的主要收益来源之一。</p>
 *
 * @param <E> 元素类型
 * @author CH
 * @since 4.0.0.42
 * @see Wraps#set()
 */
public class WrappedSet<E> extends AbstractSet<E> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 底层存储（eclipse UnifiedSet 或 JDK HashSet）。
     */
    private final Set<E> delegate;

    /**
     * 以指定集合为存储进行分装。
     *
     * @param delegate 底层存储，不可为 {@code null}
     */
    public WrappedSet(Set<E> delegate) {
        if (delegate == null) {
            throw new NullPointerException("delegate");
        }
        this.delegate = delegate;
    }

    /**
     * 取出底层存储（用于需要原始类型（如 {@code UnifiedSet}）的互操作场景）。
     *
     * @return 底层存储
     */
    public Set<E> delegate() {
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
}
