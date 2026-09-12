package com.chua.common.support.lang.process.wrapped;

import com.chua.common.support.lang.process.ProgressBar;

import java.util.Iterator;

/**
* 任何由进度条跟踪迭代过程的迭代器。
* <p>
* 该类包装了一个底层的迭代器，并在每次调用 {@code next()} 时更新进度条。
* 当迭代结束时，自动关闭进度条以释放资源。
* </p>
*
* @author CH
* @since 0.6.0
 */
public class ProgressBarWrappedIterator<T> implements Iterator<T>, AutoCloseable {

    /**
    * 被包装的底层迭代器，用于提供实际的元素遍历逻辑。
     */
    private final Iterator<T> underlying;

    /**
    * 用于跟踪当前进度的进度条实例。
     */
    private final ProgressBar pb;

    /**
    * 构造函数，初始化底层迭代器和进度条。
    *
    * @param underlying 底层迭代器，不能为 null。
    * @param pb         进度条实例，不能为 null。
     */
    public ProgressBarWrappedIterator(Iterator<T> underlying, ProgressBar pb) {
        this.underlying = underlying;
        this.pb = pb;
    }

    /**
    * 获取关联的进度条实例。
    *
    * @return 进度条对象。
     */
    public ProgressBar getProgressBar() {
        return pb;
    }

    /**
    * 检查是否还有下一个元素。
    * <p>
    * 如果底层迭代器没有更多元素，则关闭进度条并返回 false。
    * </p>
    *
    * @return 如果还有下一个元素则返回 true，否则返回 false。
     */
    @Override
    public boolean hasNext() {
        boolean r = underlying.hasNext();
        if (!r) {
            pb.close();
        }
        return r;
    }

    /**
    * 返回迭代的下一个元素，并推进进度条。
    *
    * @return 下一个元素。
    * @throws java.util.NoSuchElementException 如果没有更多元素。
     */
    @Override
    public T next() {
        T r = underlying.next();
        pb.step();
        return r;
    }

    /**
    * 从结果中移除最后一个返回的元素。
    * <p>
    * 此操作委托给底层迭代器执行。
    * </p>
    *
    * @throws UnsupportedOperationException 如果底层迭代器不支持此操作。
    * @throws IllegalStateException           如果尚未调用 {@code next} 或上一次调用 {@code remove} 后未调用 {@code next}。
     */
    @Override
    public void remove() {
        underlying.remove();
    }

    /**
    * 关闭进度条，释放相关资源。
    * <p>
    * 该方法确保在迭代完成后正确清理进度条状态。
    * </p>
     */
    @Override
    public void close() {
        pb.close();
    }
}