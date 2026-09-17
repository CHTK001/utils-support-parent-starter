package com.chua.common.support.collection;

import java.util.*;

/**
* 可重复元素的集合（Multiset），记录每个元素的出现次数。
* <p>
* 基于 {@link HashMap} 实现，允许重复元素，并提供计数查询能力。
* </p>
*
* <h3>适用场景</h3>
* <ul>
*   <li><strong>词频统计</strong> — 统计一段文本中各单词出现次数</li>
*   <li><strong>访问计数</strong> — 统计各 URL、用户、资源的访问频次</li>
*   <li><strong>重复度分析</strong> — 快速找出重复次数最多/最少的元素</li>
*   <li><strong>基数去重</strong> — 既需要去重，又需要知道每个元素重复了多少次</li>
* </ul>
*
* <p>
* 线程不安全，多线程环境请自行加锁或使用 {@link Collections#synchronizedMap(Map)} 包装。
* </p>
*
* @param <E> 元素类型
* @author CH
* @since 4.0.0.42
* @version 1.0.0
* @see #uniqueElements()
* @see #entrySet()
 */
public class Multiset<E> extends AbstractSet<E> {

    /**
    * 内部存储 Map
    */
    private final HashMap<E, Integer> countMap;

    /**
    * 创建空 Multiset。
    *
    * @param <E> 元素类型
    * @return Multiset 实例
    */
    public static <E> Multiset<E> of() {
        return new Multiset<>();
    }

    /**
    * 从集合创建 Multiset，统计各元素出现次数。
    *
    * @param c 初始集合
    * @param <E> 元素类型
    * @return Multiset 实例
    */
    public static <E> Multiset<E> of(Collection<? extends E> c) {
        Multiset<E> bag = new Multiset<>();
        if (c != null) {
            bag.addAll(c);
        }
        return bag;
    }

    /**
    * 构造方法，创建空 Multiset。
    */
    public Multiset() {
        this.countMap = new HashMap<>();
    }

    /**
    * 构造方法，使用指定初始容量创建 Multiset。
    *
    * @param initialCapacity 初始容量
    */
    public Multiset(int initialCapacity) {
        this.countMap = new HashMap<>(initialCapacity);
    }

    // ==================== 计数操作 ====================

    /**
    * 增加指定元素的计数（相当于 {@code increment(element, 1)}）。
    *
    * @param element 元素
    * @return 增加后的计数值
    */
    public int increment(E element) {
        return countMap.merge(element, 1, Integer::sum);
    }

    /**
    * 增加指定元素的计数，可指定增量。
    *
    * @param element 元素
    * @param count   增量，必须大于 0
    * @return 增加后的计数值
    * @throws IllegalArgumentException 如果 count 小于等于 0
    */
    public int increment(E element, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("增量必须大于 0");
        }
        return countMap.merge(element, count, Integer::sum);
    }

    /**
    * 减少指定元素的计数（每次减 1）。
    *
    * @param element 元素
    * @return 减少后的计数值，如果元素不存在返回 0
    */
    public int decrement(E element) {
        Integer count = countMap.get(element);
        if (count == null) {
            return 0;
        }
        if (count == 1) {
            countMap.remove(element);
            return 0;
        }
        countMap.put(element, count - 1);
        return count - 1;
    }

    /**
    * 完全移除指定元素（清零计数）。
    *
    * @param element 元素
    * @return 被移除的计数值，如果元素不存在返回 0
    */
    public int removeAll(E element) {
        Integer count = countMap.remove(element);
        return count != null ? count : 0;
    }

    /**
    * 获取指定元素的计数值。
    *
    * @param element 元素
    * @return 计数值，如果元素不存在返回 0
    */
    public int count(E element) {
        Integer count = countMap.get(element);
        return count != null ? count : 0;
    }

    /**
    * 返回 Multiset 中不同元素的数量（基数）。
    *
    * @return 不同元素的数量
    */
    @Override
    public int size() {
        return countMap.size();
    }

    /**
    * 返回所有元素的总出现次数（含重复）。
    *
    * @return 元素总次数
    */
    public int totalCount() {
        int total = 0;
        for (int count : countMap.values()) {
            total += count;
        }
        return total;
    }

    // ==================== Set 接口实现 ====================

    @Override
    /** 是否Empty */
    public boolean isEmpty() {
        return countMap.isEmpty();
    }

    @Override
    /** Contains */
    public boolean contains(Object o) {
        Integer count = countMap.get(o);
        return count != null && count > 0;
    }

    @Override
    /** Iterator */
    public Iterator<E> iterator() {
        return countMap.keySet().iterator();
    }

    @Override
    /** ToArray */
    public Object[] toArray() {
        return countMap.keySet().toArray();
    }

    @Override
    /** ToArray */
    public <T> T[] toArray(T[] a) {
        return countMap.keySet().toArray(a);
    }

    @Override
    /** 添加 */
    public boolean add(E e) {
        increment(e);
        return true;
    }

    @Override
    /** 移除 */
    public boolean remove(Object o) {
        Integer oldCount = countMap.remove(o);
        return oldCount != null && oldCount > 0;
    }

    @Override
    /** ContainsAll */
    public boolean containsAll(Collection<?> c) {
        for (Object o : c) {
            if (!contains(o)) {
                return false;
            }
        }
        return true;
    }

    @Override
    /** 添加All */
    public boolean addAll(Collection<? extends E> c) {
        boolean modified = false;
        for (E e : c) {
            int old = count(e);
            increment(e);
            modified |= count(e) > old;
        }
        return modified;
    }

    @Override
    /** RetainAll */
    public boolean retainAll(Collection<?> c) {
        boolean modified = false;
        for (Iterator<E> it = countMap.keySet().iterator(); it.hasNext(); ) {
            E e = it.next();
            if (!c.contains(e)) {
                it.remove();
                modified = true;
            }
        }
        return modified;
    }

    @Override
    /** 移除All */
    public boolean removeAll(Collection<?> c) {
        boolean modified = false;
        for (Object o : c) {
            Integer oldCount = countMap.remove(o);
            modified |= oldCount != null && oldCount > 0;
        }
        return modified;
    }

    @Override
    /** Clear */
    public void clear() {
        countMap.clear();
    }

    // ==================== Multiset 特有 API ====================

    /**
    * 返回所有唯一元素的集合（去重后的元素集合）。
    *
    * @return 唯一元素集合
    */
    public Set<E> uniqueElements() {
        return Collections.unmodifiableSet(countMap.keySet());
    }

    /**
    * 返回所有元素的计数字典（元素 -> 出现次数）。
    *
    * @return 不可修改的计数字典
    */
    public Map<E, Integer> entrySet() {
        return Collections.unmodifiableMap(countMap);
    }

    /**
    * 返回出现次数最多的元素集合（可能有多个元素并列最大）。
    *
    * @return 出现次数最多的元素集合
    */
    public Set<E> maxElements() {
        int max = 0;
        for (int count : countMap.values()) {
            if (count > max) {
                max = count;
            }
        }
        if (max == 0) {
            return Collections.emptySet();
        }
        Set<E> result = new HashSet<>();
        for (Map.Entry<E, Integer> entry : countMap.entrySet()) {
            if (entry.getValue() == max) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
    * 返回出现次数最少的元素集合（可能有多个元素并列最小）。
    *
    * @return 出现次数最少的元素集合
    */
    public Set<E> minElements() {
        if (countMap.isEmpty()) {
            return Collections.emptySet();
        }
        int min = Integer.MAX_VALUE;
        for (int count : countMap.values()) {
            if (count < min) {
                min = count;
            }
        }
        Set<E> result = new HashSet<>();
        for (Map.Entry<E, Integer> entry : countMap.entrySet()) {
            if (entry.getValue() == min) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    @Override
    /** 判断相等 */
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Multiset<?> other)) {
            return false;
        }
        return countMap.equals(other.countMap);
    }

    @Override
    /** HashCode */
    public int hashCode() {
        return countMap.hashCode();
    }

    @Override
    /** ToString */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<E, Integer> entry : countMap.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }
}
