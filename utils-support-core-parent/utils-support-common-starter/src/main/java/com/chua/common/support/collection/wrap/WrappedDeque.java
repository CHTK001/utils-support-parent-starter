package com.chua.common.support.collection.wrap;

import java.io.Serializable;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 双端队列分装对象：内部持有可切换的双端队列存储，对外表现为标准 {@link Deque}。
 *
 * <p>Eclipse Collections 13.0.0 没有双端队列实现，两个内置后端统一使用
 * {@code ArrayDeque} 存储（见 {@link CollectionBackend} 契约说明）；
 * 本类型与 {@link WrappedQueue} 一样，意义在于统一创建入口
 * （{@link Wraps#deque()}）、预留后端切换点与一致的行为约定。</p>
 *
 * <p>因 {@link Deque} 继承 {@link java.util.Collection} 但不继承任何抽象骨架类，
 * 本类手工实现全部抽象方法并直接委托底层存储；{@code reversed()}
 * 沿用接口默认实现（基于本对象的逆序视图），与直接使用底层存储的行为一致。</p>
 *
 * <p><b>相等性说明：</b>{@code equals} / {@code hashCode} / {@code toString}
 * 透传底层存储（{@code ArrayDeque} 为同一性比较），对方为本包装类型时自动解包，
 * 不改变底层语义——详见 {@link WrappedQueue}。</p>
 *
 * @param <E> 元素类型
 * @author CH
 * @since 4.0.0.42
 * @see Wraps#deque()
 */
public class WrappedDeque<E> implements Deque<E>, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 底层存储（两后端均为 ArrayDeque）。
     */
    private final Deque<E> delegate;

    /**
     * 以指定双端队列为存储进行分装。
     *
     * @param delegate 底层存储，不可为 {@code null}
     */
    public WrappedDeque(Deque<E> delegate) {
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
    public Deque<E> delegate() {
        return delegate;
    }

    // ============================== Collection ==============================

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
    public void clear() {
        delegate.clear();
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

    // ========================== SequencedCollection =========================

    @Override
    public void addFirst(E e) {
        delegate.addFirst(e);
    }

    @Override
    public void addLast(E e) {
        delegate.addLast(e);
    }

    @Override
    public E getFirst() {
        return delegate.getFirst();
    }

    @Override
    public E getLast() {
        return delegate.getLast();
    }

    @Override
    public E removeFirst() {
        return delegate.removeFirst();
    }

    @Override
    public E removeLast() {
        return delegate.removeLast();
    }

    // ================================= Deque =================================

    @Override
    public boolean offerFirst(E e) {
        return delegate.offerFirst(e);
    }

    @Override
    public boolean offerLast(E e) {
        return delegate.offerLast(e);
    }

    @Override
    public E pollFirst() {
        return delegate.pollFirst();
    }

    @Override
    public E pollLast() {
        return delegate.pollLast();
    }

    @Override
    public E peekFirst() {
        return delegate.peekFirst();
    }

    @Override
    public E peekLast() {
        return delegate.peekLast();
    }

    @Override
    public boolean removeFirstOccurrence(Object o) {
        return delegate.removeFirstOccurrence(o);
    }

    @Override
    public boolean removeLastOccurrence(Object o) {
        return delegate.removeLastOccurrence(o);
    }

    @Override
    public void push(E e) {
        delegate.push(e);
    }

    @Override
    public E pop() {
        return delegate.pop();
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
    public Iterator<E> descendingIterator() {
        return delegate.descendingIterator();
    }

    // =============================== Object =================================

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof WrappedDeque) {
            o = ((WrappedDeque<?>) o).delegate;
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
