package com.chua.common.support.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * B+ 树实现。
 *
 * <p>所有数据存储在叶子节点，内部节点仅作为索引；叶子节点通过链表串联，
 * 范围查询无需回溯，适合数据库索引与内存缓存场景。
 * 对 1000 万级键值，典型高度 ≤ 4，单次查找仅需 3~4 次指针跳转。</p>
 *
 * @param <K> 键类型，须实现 {@link Comparable}
 * @param <V> 值类型
 * @author CH
 * @since 4.0.0.42
 */
public class BPlusTree<K extends Comparable<K>, V> implements TreeEngine<K, V> {

    private final int order;
    BPlusTreeNode<K, V> root;
    private int size;

    /**
     * 构造 B+ 树，指定阶数。
     *
     * @param order B+ 树阶数，建议 100~512；阶数越大单节点容量越高，高度越低
     * @throws IllegalArgumentException 当 order &lt; 3 时
     */
    public BPlusTree(int order) {
        if (order < 3) {
            throw new IllegalArgumentException("B+ tree order must be >= 3, got: " + order);
        }
        this.order = order;
        this.root = new BPlusTreeNode<>(true);
        this.size = 0;
    }

    @Override
    public String type() {
        return "B_PLUS_TREE";
    }

    // ==================== get ====================

    @Override
    public Optional<V> get(K key) {
        if (key == null) {
            return Optional.empty();
        }
        return findByKey(root, key);
    }

    @Override
    public boolean containsKey(K key) {
        return get(key).isPresent();
    }

    /**
     * 递归精确查找键对应的值。
     *
     * @param node 当前节点
     * @param key  查询键
     * @return 结果包装
     */
    private Optional<V> findByKey(BPlusTreeNode<K, V> node, K key) {
        int i = 0;
        while (i < node.keys.size() && node.keys.get(i).compareTo(key) < 0) {
            i++;
        }
        if (node.leaf) {
            if (i < node.keys.size() && Objects.equals(node.keys.get(i), key)) {
                return Optional.of(node.values.get(i));
            }
            return Optional.empty();
        }
        return findByKey(node.children.get(i), key);
    }

    // ==================== range ====================

    @Override
    public List<Map.Entry<K, V>> range(K from, K to) {
        if (from == null || to == null) {
            return Collections.emptyList();
        }
        List<Map.Entry<K, V>> result = new ArrayList<>();
        locateLeaf(root, from, to, result);
        return result;
    }

    /**
     * 递归定位到叶子节点并收集区间内所有条目。
     *
     * @param node   当前节点
     * @param from   下界（含）
     * @param to     上界（不含）
     * @param result 结果收集列表
     */
    private void locateLeaf(BPlusTreeNode<K, V> node, K from, K to, List<Map.Entry<K, V>> result) {
        if (node.leaf) {
            for (int i = 0; i < node.keys.size(); i++) {
                K k = node.keys.get(i);
                if (k.compareTo(from) >= 0 && k.compareTo(to) < 0) {
                    result.add(Map.entry(k, node.values.get(i)));
                }
            }
            return;
        }
        int i = 0;
        while (i < node.keys.size() && node.keys.get(i).compareTo(from) < 0) {
            i++;
        }
        for (int j = i; j <= node.keys.size(); j++) {
            if (j > 0 && node.keys.get(j - 1).compareTo(to) >= 0) {
                break;
            }
            locateLeaf(node.children.get(j), from, to, result);
        }
    }

    // ==================== put ====================

    @Override
    public Optional<V> put(K key, V value) {
        if (key == null) {
            return Optional.empty();
        }
        SplitResult<K, V> result = insert(root, key, value);
        if (result.split != null) {
            BPlusTreeNode<K, V> newRoot = new BPlusTreeNode<>(false);
            newRoot.keys.add(result.midKey);
            newRoot.children.add(root);
            newRoot.children.add(result.split);
            root = newRoot;
        }
        if (result.oldValue == null && get(key).isPresent()) {
            size++;
        }
        return result.oldValue != null ? Optional.of(result.oldValue) : Optional.empty();
    }

    /**
     * 递归插入键值对，返回分裂结果。
     *
     * @param node  当前节点
     * @param key   键
     * @param value 值
     * @return 分裂结果
     */
    private SplitResult<K, V> insert(BPlusTreeNode<K, V> node, K key, V value) {
        int i = 0;
        while (i < node.keys.size() && node.keys.get(i).compareTo(key) < 0) {
            i++;
        }
        if (node.leaf) {
            if (i < node.keys.size() && Objects.equals(node.keys.get(i), key)) {
                V old = node.values.get(i);
                node.values.set(i, value);
                return new SplitResult<>(null, key, old);
            }
            node.keys.add(i, key);
            node.values.add(i, value);
            if (!node.isFull(order)) {
                return new SplitResult<>(null, key, null);
            }
            return splitLeaf(node);
        }
        SplitResult<K, V> sub = insert(node.children.get(i), key, value);
        if (sub.split == null) {
            return sub;
        }
        node.keys.add(i, sub.midKey);
        node.children.add(i + 1, sub.split);
        if (!node.isFull(order)) {
            return new SplitResult<>(null, key, null);
        }
        return splitInternal(node);
    }

    /**
     * 分裂叶子节点：左半留原位，右半新建，返回中间键和右半节点。
     *
     * @param node 待分裂的叶子节点
     * @return 分裂结果
     */
    private SplitResult<K, V> splitLeaf(BPlusTreeNode<K, V> node) {
        int mid = node.keys.size() / 2;
        BPlusTreeNode<K, V> right = new BPlusTreeNode<>(true);
        right.next = node.next;
        node.next = right;
        for (int i = mid; i < node.keys.size(); i++) {
            right.keys.add(node.keys.remove(mid));
            right.values.add(node.values.remove(mid));
        }
        K midKey = right.keys.get(0);
        return new SplitResult<>(right, midKey, null);
    }

    /**
     * 分裂内部节点：中间键提升，左右两半分别保留。
     *
     * @param node 待分裂的内部节点
     * @return 分裂结果
     */
    private SplitResult<K, V> splitInternal(BPlusTreeNode<K, V> node) {
        int mid = node.keys.size() / 2;
        K promote = node.keys.get(mid);
        BPlusTreeNode<K, V> right = new BPlusTreeNode<>(false);
        for (int i = mid + 1; i < node.keys.size(); i++) {
            right.keys.add(node.keys.remove(mid + 1));
        }
        for (int i = mid + 1; i < node.children.size(); i++) {
            right.children.add(node.children.remove(mid + 1));
        }
        node.keys.remove(mid);
        return new SplitResult<>(right, promote, null);
    }

    // ==================== remove ====================

    @Override
    public Optional<V> remove(K key) {
        if (key == null) {
            return Optional.empty();
        }
        Optional<V> oldValue = get(key);
        if (oldValue.isEmpty()) {
            return Optional.empty();
        }
        delete(root, key);
        if (root.leaf && root.keys.isEmpty()) {
            root = root.next;
            if (root == null) {
                root = new BPlusTreeNode<>(true);
            }
        }
        size--;
        return oldValue;
    }

    /**
     * 递归删除键（简化版：先查后删，不做合并借位优化）。
     *
     * @param node 当前节点
     * @param key  待删除键
     */
    private void delete(BPlusTreeNode<K, V> node, K key) {
        int i = 0;
        while (i < node.keys.size() && node.keys.get(i).compareTo(key) < 0) {
            i++;
        }
        if (node.leaf) {
            if (i < node.keys.size() && Objects.equals(node.keys.get(i), key)) {
                node.keys.remove(i);
                node.values.remove(i);
            }
            return;
        }
        delete(node.children.get(i), key);
        if (i < node.keys.size() && Objects.equals(node.keys.get(i), key)) {
            node.keys.remove(i);
            node.children.remove(i);
            if (!node.children.isEmpty()) {
                node.keys.add(i, node.children.get(i).keys.get(0));
            }
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public void clear() {
        root = new BPlusTreeNode<>(true);
        size = 0;
    }

    @Override
    public TreeNode<K, V> toBinaryTree() {
        return BinaryTreeConverter.bPlusToBinary(this);
    }

    /**
     * 内部分裂结果记录。
     *
     * @param <K>    键类型
     * @param <V>    值类型
     * @param split  分裂产生的右半节点
     * @param midKey 提升的中间键
     * @param oldValue 被替换的旧值（更新操作时非空）
     */
    private record SplitResult<K, V>(
            BPlusTreeNode<K, V> split,
            K midKey,
            V oldValue
    ) {
    }

    @Override
    public String toString() {
        return "BPlusTree{order=" + order + ", size=" + size + ", height=" + height() + "}";
    }

    private int height() {
        int h = 0;
        BPlusTreeNode<K, V> cur = root;
        while (cur != null && !cur.leaf) {
            h++;
            cur = cur.children.get(0);
        }
        return h + 1;
    }
}
