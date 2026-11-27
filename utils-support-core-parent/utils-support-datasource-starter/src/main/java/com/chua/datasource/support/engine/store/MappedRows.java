package com.chua.datasource.support.engine.store;

import java.util.AbstractList;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 由 {@link MappedTable} 承载的行列表。
 *
 * <p>本类实现 {@link AbstractList} 要求的可变更操作，其余操作（迭代器、
 * 子列表、排序、批量删除等）沿用 JDK 默认实现——它们最终都会落到
 * {@link #get(int)} / {@link #set(int, Object)} / {@link #add(int, Object)} /
 * {@link #remove(int)} / {@link #clear()} 上。</p>
 *
 * <p><b>注意</b>：{@link #get(int)} 返回的是反序列化副本，修改字段不会写回存储；
 * 更新必须使用 {@link #set(int, Object)}。引擎侧的就地改值路径已按此改造。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
final class MappedRows extends AbstractList<Object> {

    /**
     * 承载表。
     */
    private final MappedTable table;

    MappedRows(MappedTable table) {
        this.table = table;
    }

    @Override
    public Object get(int index) {
        return table.get(index);
    }

    @Override
    public int size() {
        return table.size();
    }

    @Override
    public Object set(int index, Object element) {
        Object previous = table.set(index, element);
        modCount++;
        return previous;
    }

    @Override
    public void add(int index, Object element) {
        table.add(index, element);
        modCount++;
    }

    @Override
    public boolean add(Object element) {
        add(size(), element);
        return true;
    }

    @Override
    public boolean addAll(Collection<?> elements) {
        Objects.requireNonNull(elements, "elements");
        if (elements.isEmpty()) {
            return false;
        }
        table.addAll(elements);
        modCount++;
        return true;
    }

    @Override
    public Object remove(int index) {
        Object previous = table.removeAt(index);
        modCount++;
        return previous;
    }

    @Override
    public void clear() {
        if (table.size() == 0) {
            return;
        }
        table.clear();
        modCount++;
    }

    /**
     * 按谓词删除：在存储侧一次扫描完成，避免 {@link AbstractList} 默认实现
     * 逐个 {@code remove(int)} 带来的 O(n²) 索引前移。
     *
     * @param filter 谓词
     * @return 是否有行被删除
     */
    @Override
    public boolean removeIf(Predicate<? super Object> filter) {
        Objects.requireNonNull(filter, "filter");
        boolean changed = table.removeIfRows(filter::test);
        if (changed) {
            modCount++;
        }
        return changed;
    }
}
