package com.chua.common.support.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * B 树实现（优化版）。
 *
 * <p>与 B+ 树不同，B 树的键值对可存储于内部节点和叶子节点；
 * 查找时可能在任意层命中，平均比较次数略低于 B+ 树。适合点查密集型场景。</p>
 *
 * @param <K> 键类型，须实现 {@link Comparable}
 * @param <V> 值类型
 * @author CH
 * @since 4.0.0.42
 */
public class BTree<K extends Comparable<K>, V> implements TreeEngine<K, V> {

    private final int order;
    BTreeNode<K, V> root;
    private int size;

    public BTree(int order) {
        if (order < 3) throw new IllegalArgumentException("B tree order must be >= 3, got: " + order);
        this.order = order;
        // 预分配容量
        this.root = new BTreeNode<>(true, order * 4);
        this.size = 0;
    }

    @Override
    public String type() { return "B_TREE"; }

    // ==================== get ====================

    @Override
    public Optional<V> get(K key) {
        if (key == null) return Optional.empty();
        // 迭代查找，避免递归栈开销
        BTreeNode<K, V> node = root;
        while (true) {
            List<K> keys = node.keys;
            int i = 0;
            int n = keys.size();
            while (i < n && keys.get(i).compareTo(key) < 0) i++;
            if (i < n && Objects.equals(keys.get(i), key)) {
                return Optional.of(node.values.get(i));
            }
            if (node.leaf) return Optional.empty();
            node = node.children.get(i);
        }
    }

    @Override
    public boolean containsKey(K key) {
        return get(key).isPresent();
    }

    // ==================== range ====================

    @Override
    public List<Map.Entry<K, V>> range(K from, K to) {
        if (from == null || to == null) return Collections.emptyList();
        int estimatedSize = Math.min(size, (size / Math.max(order / 2, 1)) * 2);
        List<Map.Entry<K, V>> result = new ArrayList<>(estimatedSize);
        collectRange(root, from, to, result);
        return result;
    }

    private void collectRange(BTreeNode<K, V> node, K from, K to, List<Map.Entry<K, V>> result) {
        // 收集当前节点在范围内的键
        List<K> keys = node.keys;
        List<V> values = node.values;
        int n = keys.size();
        for (int i = 0; i < n; i++) {
            K k = keys.get(i);
            if (k.compareTo(from) >= 0 && k.compareTo(to) < 0) {
                result.add(Map.entry(k, values.get(i)));
            }
        }
        if (node.leaf) return;
        // 遍历所有子节点
        List<BTreeNode<K, V>> children = node.children;
        for (BTreeNode<K, V> child : children) {
            collectRange(child, from, to, result);
        }
    }

    // ==================== put ====================

    @Override
    public Optional<V> put(K key, V value) {
        if (key == null) return Optional.empty();
        boolean existed = containsKey(key);
        SplitResult<K, V> split = splitInsert(root, key, value);
        if (split != null) {
            BTreeNode<K, V> newRoot = new BTreeNode<>(false, order * 2);
            newRoot.keys.add(split.promotedKey);
            newRoot.values.add(split.promotedValue);
            newRoot.children.add(split.left);
            newRoot.children.add(split.right);
            root = newRoot;
        }
        if (!existed) size++;
        return existed ? Optional.of(get(key).get()) : Optional.empty();
    }

    /**
     * 分裂插入：返回 null 表示无需分裂，否则返回分裂结果。
     * 分裂后 node 本身变为左半部分。
     */
    private SplitResult<K, V> splitInsert(BTreeNode<K, V> node, K key, V value) {
        List<K> keys = node.keys;
        List<V> values = node.values;
        int i = 0;
        int n = keys.size();
        while (i < n && keys.get(i).compareTo(key) < 0) i++;

        if (node.leaf) {
            // 叶子节点：直接插入
            if (i < n && Objects.equals(keys.get(i), key)) {
                values.set(i, value);
                return null;
            }
            keys.add(i, key);
            values.add(i, value);
            if (keys.size() < order) return null;
            return doSplit(node);
        }

        // 内部节点：递归插入子节点
        List<BTreeNode<K, V>> children = node.children;
        SplitResult<K, V> sub = splitInsert(children.get(i), key, value);
        if (sub == null) return null;

        // 子节点分裂，promotedKey 插入 keys[i]，children[i] 替换为 left 和 right
        keys.add(i, sub.promotedKey);
        values.add(i, sub.promotedValue);
        children.remove(i);
        children.add(i, sub.left);
        children.add(i + 1, sub.right);

        if (keys.size() < order) return null;
        return doSplit(node);
    }

    /**
     * 分裂节点：中间键提升，左右两半分别保留。
     */
    private SplitResult doSplit(BTreeNode<K, V> node) {
        List<K> keys = node.keys;
        List<V> values = node.values;
        int n = keys.size();
        int mid = n / 2;
        K promoteKey = keys.get(mid);
        V promoteValue = values.get(mid);

        BTreeNode<K, V> right = new BTreeNode<>(node.leaf, n - mid);
        // 拷贝右半部分
        for (int j = mid + 1; j < n; j++) {
            right.keys.add(keys.get(j));
            right.values.add(values.get(j));
        }
        if (!node.leaf) {
            List<BTreeNode<K, V>> children = node.children;
            for (int j = mid + 1; j <= n; j++) {
                right.children.add(children.get(j));
            }
            children.subList(mid + 1, n + 1).clear();
        }
        keys.subList(mid, n).clear();
        values.subList(mid, n).clear();

        return new SplitResult(promoteKey, promoteValue, node, right);
    }

    // ==================== remove ====================

    @Override
    public Optional<V> remove(K key) {
        if (key == null) return Optional.empty();
        Optional<V> oldValue = get(key);
        if (oldValue.isEmpty()) return Optional.empty();
        delete(root, key);
        if (root.leaf && root.keys.isEmpty()) root = new BTreeNode<>(true, order);
        size--;
        return oldValue;
    }

    private void delete(BTreeNode<K, V> node, K key) {
        List<K> keys = node.keys;
        int i = 0;
        int n = keys.size();
        while (i < n && keys.get(i).compareTo(key) < 0) i++;
        if (node.leaf) {
            if (i < n && Objects.equals(keys.get(i), key)) {
                keys.remove(i);
                node.values.remove(i);
            }
            return;
        }
        if (i < n && Objects.equals(keys.get(i), key)) {
            BTreeNode<K, V> leftChild = node.children.get(i);
            if (!leftChild.keys.isEmpty()) {
                K pred = findPredecessor(leftChild);
                keys.set(i, pred);
                delete(leftChild, pred);
            } else {
                K succ = findSuccessor(node.children.get(i + 1));
                keys.set(i, succ);
                delete(node.children.get(i + 1), succ);
            }
            return;
        }
        delete(node.children.get(i), key);
    }

    private K findPredecessor(BTreeNode<K, V> node) {
        while (!node.leaf) node = node.children.get(node.keys.size() - 1);
        return node.keys.get(node.keys.size() - 1);
    }

    private K findSuccessor(BTreeNode<K, V> node) {
        while (!node.leaf) node = node.children.get(0);
        return node.keys.get(0);
    }

    @Override
    public int size() { return size; }
    @Override
    public boolean isEmpty() { return size == 0; }
    @Override
    public void clear() { root = new BTreeNode<>(true, order); size = 0; }
    @Override
    public TreeNode<K, V> toBinaryTree() { return BinaryTreeConverter.bTreeToBinary(this); }
    @Override
    public String toString() { return "BTree{order=" + order + ", size=" + size + "}"; }

    BTreeNode<K, V> getRoot() { return root; }

    /** 分裂结果 */
    private static class SplitResult<K, V> {
        final K promotedKey;
        final V promotedValue;
        final BTreeNode<K, V> left;
        final BTreeNode<K, V> right;

        SplitResult(K k, V v, BTreeNode<K, V> left, BTreeNode<K, V> right) {
            this.promotedKey = k;
            this.promotedValue = v;
            this.left = left;
            this.right = right;
        }
    }
}
