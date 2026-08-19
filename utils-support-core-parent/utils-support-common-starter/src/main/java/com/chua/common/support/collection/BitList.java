package com.chua.common.support.collection;

import java.util.*;

/**
 * 基于 {@link BitSet} 的位列表，提供 {@link List}{@code <Boolean>} 语义与直接位操作。
 * <p>
 * 内部使用 {@link BitSet} 存储布尔值，相比 {@code ArrayList<Boolean>} 更节省内存，
 * 适合大数据量、高并发的位标记场景。
 * </p>
 *
 * <h3>适用场景</h3>
 * <ul>
 *   <li><strong>布尔标记位数组</strong> — 替代 {@code List<Boolean>}，节省内存</li>
 *   <li><strong>布隆过滤器辅助层</strong> — 结合 {@link #set(int, boolean)} 做位标记</li>
 *   <li><strong>位图统计</strong> — 快速统计置位个数 {@link #cardinality()}、是否全 0/全 1</li>
 *   <li><strong>大容量去重标记</strong> — 用位索引代替对象，降低内存占用</li>
 * </ul>
 *
 * <p>
 * 线程不安全，多线程环境请自行加锁。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 * @see BitSet
 */
public class BitList extends AbstractList<Boolean> implements RandomAccess {

    /**
     * 内部位存储
     */
    private BitSet bits;

    /**
     * 创建容量为 0 的位列表。
     *
     * @return BitList 实例
     */
    public static BitList of() {
        return new BitList();
    }

    /**
     * 创建指定初始位数的位列表。
     *
     * @param bitLength 初始位数
     * @return BitList 实例
     */
    public static BitList of(int bitLength) {
        return new BitList(bitLength);
    }

    /**
     * 从布尔数组创建位列表。
     *
     * @param values 布尔数组
     * @return BitList 实例
     */
    public static BitList of(boolean[] values) {
        BitList list = new BitList(values.length);
        for (int i = 0; i < values.length; i++) {
            if (values[i]) {
                list.set(i, true);
            }
        }
        return list;
    }

    /**
     * 从 {@link Collection}{@code <Boolean>} 创建位列表。
     *
     * @param values 布尔集合
     * @return BitList 实例
     */
    public static BitList of(Collection<Boolean> values) {
        BitList list = new BitList(values.size());
        int index = 0;
        for (Boolean b : values) {
            if (Boolean.TRUE.equals(b)) {
                list.set(index, true);
            }
            index++;
        }
        return list;
    }

    /**
     * 构造方法，创建容量为 0 的位列表。
     */
    public BitList() {
        this.bits = new BitSet();
    }

    /**
     * 构造方法，创建指定位数的位列表。
     *
     * @param bitLength 初始位数
     */
    public BitList(int bitLength) {
        this.bits = new BitSet(bitLength);
    }

    // ==================== 位操作 API ====================

    /**
     * 获取指定位的值。
     *
     * @param index 位索引
     * @return 如果该位为 1 返回 true，否则返回 false
     */
    public boolean getBit(int index) {
        return bits.get(index);
    }

    /**
     * 设置指定位的值。
     *
     * @param index 位索引
     * @param value 位值
     */
    public void setBit(int index, boolean value) {
        bits.set(index, value);
    }

    /**
     * 将指定位设为 1。
     *
     * @param index 位索引
     */
    public void setBit(int index) {
        bits.set(index);
    }

    /**
     * 将指定位设为 0。
     *
     * @param index 位索引
     */
    public void clearBit(int index) {
        bits.clear(index);
    }

    /**
     * 切换指定位的值（0 变 1，1 变 0）。
     *
     * @param index 位索引
     */
    public void flipBit(int index) {
        bits.flip(index);
    }

    /**
     * 返回置位个数（为 1 的位的数量）。
     *
     * @return 置位个数
     */
    public int cardinality() {
        return bits.cardinality();
    }

    /**
     * 返回下一个置位的位置，从 {@code fromIndex} 开始搜索。
     *
     * @param fromIndex 起始索引（包含）
     * @return 下一个置位的索引，不存在返回 {@code -1}
     */
    public int nextSetBit(int fromIndex) {
        return bits.nextSetBit(fromIndex);
    }

    /**
     * 返回下一个清零的位置，从 {@code fromIndex} 开始搜索。
     *
     * @param fromIndex 起始索引（包含）
     * @return 下一个清零的索引，不存在返回 {@code -1}
     */
    public int nextClearBit(int fromIndex) {
        return bits.nextClearBit(fromIndex);
    }

    /**
     * 返回当前位列表的位数（最高置位 + 1）。
     *
     * @return 位数
     */
    public int bitLength() {
        return bits.length();
    }

    /**
     * 将所有位清零。
     */
    public void clearBits() {
        bits.clear();
    }

    /**
     * 对所有位执行逻辑与操作。
     *
     * @param other 另一个位列表
     */
    public void and(BitList other) {
        bits.and(other.bits);
    }

    /**
     * 对所有位执行逻辑或操作。
     *
     * @param other 另一个位列表
     */
    public void or(BitList other) {
        bits.or(other.bits);
    }

    /**
     * 对所有位执行逻辑异或操作。
     *
     * @param other 另一个位列表
     */
    public void xor(BitList other) {
        bits.xor(other.bits);
    }

    /**
     * 对所有位取反。
     */
    public void not() {
        bits.flip(0, bits.length());
    }

    // ==================== List 接口实现 ====================

    @Override
    public int size() {
        return bits.length();
    }

    @Override
    public boolean isEmpty() {
        return bits.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        if (!(o instanceof Boolean)) {
            return false;
        }
        Boolean b = (Boolean) o;
        if (b) {
            return !bits.isEmpty();
        }
        return bits.length() > 0 && bits.nextClearBit(0) < bits.length();
    }

    @Override
    public Boolean get(int index) {
        checkElementIndex(index);
        return bits.get(index);
    }

    @Override
    public Boolean set(int index, Boolean element) {
        checkElementIndex(index);
        boolean old = bits.get(index);
        bits.set(index, element);
        return old;
    }

    @Override
    public void add(int index, Boolean element) {
        if (index != size()) {
            throw new UnsupportedOperationException("位列表不支持在中间插入，仅支持追加");
        }
        if (Boolean.TRUE.equals(element)) {
            bits.set(index);
        }
    }

    @Override
    public Boolean remove(int index) {
        checkElementIndex(index);
        boolean old = bits.get(index);
        bits.clear(index);
        return old;
    }

    @Override
    public void clear() {
        bits.clear();
    }

    @Override
    public int indexOf(Object o) {
        if (!(o instanceof Boolean)) {
            return -1;
        }
        Boolean b = (Boolean) o;
        if (b) {
            return bits.nextSetBit(0);
        }
        int nextClear = bits.nextClearBit(0);
        if (nextClear < bits.length()) {
            return nextClear;
        }
        return bits.length() > 0 ? bits.length() : -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        if (!(o instanceof Boolean)) {
            return -1;
        }
        Boolean b = (Boolean) o;
        if (b) {
            int len = bits.length();
            for (int i = len - 1; i >= 0; i--) {
                if (bits.get(i)) {
                    return i;
                }
            }
            return -1;
        }
        int len = bits.length();
        for (int i = len - 1; i >= 0; i--) {
            if (!bits.get(i)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Iterator<Boolean> iterator() {
        return new Iterator<Boolean>() {
            /** 索引位置 */
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < size();
            }

            @Override
            public Boolean next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return bits.get(index++);
            }
        };
    }

    @Override
    public List<Boolean> subList(int fromIndex, int toIndex) {
        throw new UnsupportedOperationException("位列表不支持子列表视图");
    }

    @Override
    public boolean addAll(Collection<? extends Boolean> c) {
        int oldSize = size();
        int index = oldSize;
        for (Boolean b : c) {
            if (Boolean.TRUE.equals(b)) {
                bits.set(index);
            }
            index++;
        }
        return size() > oldSize;
    }

    // ==================== 内部工具 ====================

    /**
     * 检查元素索引是否合法。
     *
     * @param index 索引
     */
    private void checkElementIndex(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("索引: " + index + ", 大小: " + size());
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof List<?> other)) {
            return false;
        }
        if (other.size() != size()) {
            return false;
        }
        ListIterator<Boolean> it1 = listIterator();
        ListIterator<?> it2 = other.listIterator();
        while (it1.hasNext() && it2.hasNext()) {
            Boolean b1 = it1.next();
            Object b2 = it2.next();
            if (!Objects.equals(b1, b2)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < size(); i++) {
            result = 31 * result + (bits.get(i) ? 1 : 0);
        }
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(bits.get(i) ? "true" : "false");
        }
        sb.append(']');
        return sb.toString();
    }
}
