package com.chua.common.support.lang.process.wrapped;

import com.chua.common.support.lang.process.ProgressBar;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;


/**
* 任何由多线程进度条跟踪并行迭代的 spliterator。
*
* @author CH
* @since 0.7.2
 */
@lombok.EqualsAndHashCode(exclude = "openChildren")
public class ProgressBarWrappedSpliterator<T> implements Spliterator<T>, AutoCloseable {

    /**
    * 底层的 spliterator。
     */
    private final Spliterator<T> underlying;

    /**
    * 用于跟踪进度的进度条对象。
     */
    private final ProgressBar pb;

    /**
    * 当前打开的子 spliterator 集合，用于并发安全地管理子迭代器。
     */
    private final Set<Spliterator<T>> openChildren;

    /**
    * 使用底层 spliterator 和进度条创建包装器。
    *
    * @param underlying 底层 spliterator
    * @param pb         进度条实例
     */
    public ProgressBarWrappedSpliterator(Spliterator<T> underlying, ProgressBar pb) {
        this(underlying, pb, Collections.synchronizedSet(new HashSet<>()));
    }

    /**
    * 私有构造函数，允许传入自定义的开放子节点集合。
    *
    * @param underlying    底层 spliterator
    * @param pb            进度条实例
    * @param openChildren  开放子节点集合
     */
    private ProgressBarWrappedSpliterator(Spliterator<T> underlying, ProgressBar pb, Set<Spliterator<T>> openChildren) {
        this.underlying = underlying;
        this.pb = pb;
        this.openChildren = openChildren;
        this.openChildren.add(this);
    }

    /**
    * 获取关联的进度条实例。
    *
    * @return 进度条对象
     */
    public ProgressBar getProgressBar() {
        return pb;
    }

    /**
    * 关闭资源，释放进度条。
     */
    @Override
    public void close() {
        pb.close();
    }

    /**
    * 注册一个子 spliterator 到开放集合中。
    *
    * @param child 需要注册的子 spliterator
     */
    private void registerChild(Spliterator<T> child) {
        openChildren.add(child);
    }

    /**
    * 移除当前实例，并在所有子节点完成后关闭进度条。
     */
    private void removeThis() {
        openChildren.remove(this);
        if (openChildren.isEmpty()) {
            close();
        }
        // 仅当没有 spliterator 正在工作时才关闭进度条
    }

    /**
    * 尝试对元素执行操作，并更新进度。
    *
    * @param action 要执行的操作
    * @return 如果存在下一个元素则返回 true，否则返回 false
     */
    @Override
    public boolean tryAdvance(Consumer<? super T> action) {
        boolean r = underlying.tryAdvance(action);
        if (r) {
            pb.step();
        } else {
            removeThis();
        }
        return r;
    }

    /**
    * 尝试将 spliterator 分割成两个部分，以便并行处理。
    *
    * @return 分割后的新 spliterator，如果无法分割则返回 null
     */
    @Override
    public Spliterator<T> trySplit() {
        Spliterator<T> u = underlying.trySplit();
        if (u != null) {
            ProgressBarWrappedSpliterator<T> child = new ProgressBarWrappedSpliterator<>(u, pb, openChildren);
            registerChild(child);
            return child;
        } else {
            return null;
        }
    }

    /**
    * 估算剩余元素的估计数量。
    *
    * @return 估计的元素数量
     */
    @Override
    public long estimateSize() {
        return underlying.estimateSize();
    }

    /**
    * 获取 spliterator 的特征值（如有序、精确等）。
    *
    * @return 特征值常量
     */
    @Override
    public int characteristics() {
        return underlying.characteristics();
    }

    /**
    * 获取比较器（如果 spliterator 是有序的）。
    *
    * @return 比较器对象，如果未定义则返回 null
     */
    @Override
    public Comparator<? super T> getComparator() {
        return underlying.getComparator();
    }

}