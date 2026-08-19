package com.chua.common.support.lang.process.wrapped;

import com.chua.common.support.lang.process.ProgressBarBuilder;

import java.util.Iterator;

/**
 * 任何可迭代对象在遍历时，都会通过进度条进行跟踪。
 *
 * @author CH
 * @since 0.6.0
 */
public class ProgressBarWrappedIterable<T> implements Iterable<T> {

    /**
     * 底层可迭代对象。
     */
    private final Iterable<T> underlying;

    /**
     * 进度条构建器。
     */
    private final ProgressBarBuilder pbb;

    /**
     * 创建 ProgressBarWrappedIterable 实例
     * @param underlying underlying
     * @param ProgressBarBuilder ProgressBarBuilder
     */
    public ProgressBarWrappedIterable(Iterable<T> underlying, ProgressBarBuilder pbb) {
        this.underlying = underlying;
        this.pbb = pbb;
    }

    /** 获取ProgressBarBuilder */
    public ProgressBarBuilder getProgressBarBuilder() {
        return pbb;
    }

    @Override
    /** Iterator */
    public ProgressBarWrappedIterator<T> iterator() {
        Iterator<T> it = underlying.iterator();
        long exactSizeIfKnown = underlying.spliterator().getExactSizeIfKnown();
        ProgressBarBuilder builder = pbb.setInitialMax(exactSizeIfKnown);
        return new ProgressBarWrappedIterator<>(it, builder.build());
    }
}