package com.chua.common.support.collection.wrap;

import java.io.Serializable;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 队列分装对象：内部持有可切换的队列存储，对外表现为标准 {@link Queue}。
 *
 * <p>Eclipse Collections 13.0.0 没有队列实现，两个内置后端统一使用
 * {@code ArrayDeque} 存储（已在 {@link CollectionBackend} 契约中如实说明），
 * 因此本类型分装的主要意义在于：统一创建入口（{@link Wraps#queue()}）、
 * 预留未来低内存后端的切换点，以及与其余包装类型一致的行为约定。</p>
 *
 * <p><b>相等性说明：</b>{@code equals} / {@code hashCode} / {@code toString}
 * 直接透传底层存储——{@code ArrayDeque} 按对象同一性比较（与 JDK 队列语义一致，
 * 队列的相等性本身依赖迭代顺序且 JDK ArrayDeque 即为同一性比较），分装不改变该语义；
 * 对方若同为本包装类型则自动解包后再比较。该语义与直接使用底层存储完全一致。</p>
 *
 * @param <E> 元素类型
 * @author CH
 * @since 4.0.0.42
 * @see Wraps#queue()
 */
public class WrappedQueue<E> extends AbstractQueue<E> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 底层存储（两后端均为 ArrayDeque）。
     */
    private final Queue<E> delegate;

    /**
     * 以指定队列为存储进行分装。
     *
     * @param delegate 底层存储，不可为 {@code null}
     */
    public WrappedQueue(Queue<E> delegate) {
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
    public Queue<E> delegate() {
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
    public boolean offer(E e) {
        return delegate.offer(e);
    }

    @Override
    public E poll() {
        return delegate.poll();
    }

    @Override
    public E peek() {
        return delegate.peek();
    }

    @Override
    public E element() {
        return delegate.element();
    }

    @Override
    public E remove() {
        return delegate.remove();
    }

    @Override
    public boolean remove(Object o) {
        return delegate.remove(o);
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

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof WrappedQueue) {
            o = ((WrappedQueue<?>) o).delegate;
        }
        return delegate.equals(o);
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
