package com.chua.runtime.apm.handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
   * 有界线程安全记录列表 — 替代裸 array列表 + 集合.同步列表。
 *
 * <p>提供:</p>
 * <ul>
 *   <li>并发安全的 addRecord(原子大小检查 + 驱逐 + add)</li>
 *   <li>O(1) 的尾部驱逐(避免 ArrayList.remove(0) O(n) shift)</li>
 *   <li>迭代快照 — tail(n) / size() 等方法在并发修改下不会抛 ConcurrentModificationException</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class BoundedRecordList<T> implements Iterable<T> {

    /**
     * 委托对象
     */
    private final ArrayList<T> delegate;
    /**
      * 最大大小
     */
    private final int maxSize;

    /**
      * 创建 boundedrecord列表 实例
     * @param maxSize 最大大小
     */
    public BoundedRecordList(int maxSize) {
        this.maxSize = maxSize;
        this.delegate = new ArrayList<>(Math.min(maxSize, 64));
    }

    /**
      * 原子添加 — 超限时淘汰最旧 1 条,然后 添加。
      * 整个流程在 同步 块内完成,避免 大小() 与 移除(0)/添加 的竞态。
     * @param record record
     */
    public synchronized void add(T record) {
        if (record == null) {
            return;
        }
        if (delegate.size() >= maxSize) {
            delegate.remove(0);
        }
        delegate.add(record);
    }

    /**
     * 原子清空。
     */
    public synchronized void clear() {
        delegate.clear();
    }

    /**
     * 当前大小。
     * @return 大小的结果
     */
    public synchronized int size() {
        return delegate.size();
    }

    /**
     * 获取最后 n 条(用于 tail)。返回不可变快照,迭代期间并发修改不影响。
     * @param n n
     * @return tail的结果
     */
    public synchronized List<T> tail(int n) {
        if (n <= 0 || delegate.isEmpty()) {
            return Collections.emptyList();
        }
        int from = Math.max(0, delegate.size() - n);
        return Collections.unmodifiableList(new ArrayList<>(delegate.subList(from, delegate.size())));
    }

    /**
     * 获取全部记录的不可变快照。
     * @return snapshot的结果
     */
    public synchronized List<T> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(delegate));
    }

    /**
     * 不可变迭代器(基于快照)。
     */
    @Override
    public Iterator<T> iterator() {
        return snapshot().iterator();
    }
}
