package com.chua.common.support.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * B 树实现。
 *
 * <p>与 B+ 树不同，B 树的键值对可存储于内部节点和叶子节点；
 * 查找时可能在任意层命中，平均比较次数略低于 B+ 树，
 * 但范围查询需遍历全树。适合点查密集型场景。</p>
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
        if (order < 3) {
            throw new IllegalArgumentException("B tree order must be >= 3, got: " + order);
        }
        this.order = order;
        this.root = new BTreeNode<>(true);
        this.root.setInitialCapacity(order);
        this.size = 0;
    }

    @Override
    public String type() {
        return "B_TREE";
    }

    @Override
    public Optional<V> get(K key) {
        if (key == null) return Optional.empty();
        return search(root, key);
    }

    @Override
    public boolean containsKey(K key) {
        return get(key).isPresent();
    }

    private Optional<V> search(BTreeNode<K, V> node, K key) {
        int i = 0;
        while (i < node.keys.size() && node.keys.get(i).compareTo(key) < 0) i++;
        if (i < node.keys.size() && Objects.equals(node.keys.get(i), key)) {
            return Optional.of(node.values.get(i));
        }
        if (node.leaf) return Optional.empty();
        return search(node.children.get(i), key);
    }

    @Override
    public List<Map.Entry<K, V>> range(K from, K to) {
        if (from == null || to == null) return Collections.emptyList();
        List<Map.Entry<K, V>> result = new ArrayList<>();
        collectRange(root, from, to, result);
        return result;
    }

    private void collectRange(BTreeNode<K, V> node, K from, K to, List<Map.Entry<K, V>> result) {
        // 收集当前节点所有在范围内的键
        for (int i = 0; i < node.keys.size(); i++) {
            K k = node.keys.get(i);
            if (k.compareTo(from) >= 0 && k.compareTo(to) < 0) {
                result.add(Map.entry(k, node.values.get(i)));
            }
        }
        if (node.leaf) return;
        // 遍历所有子节点（B树范围查询需要访问可能包含范围键的所有子树）
        for (BTreeNode<K, V> child : node.children) {
            collectRange(child, from, to, result);
        }
    }

    @Override
    public Optional<V> put(K key, V value) {
        if (key == null) return Optional.empty();
        boolean existed = containsKey(key);
        SplitResult<K, V> split = splitInsert(root, key, value);
        if (split != null) {
            BTreeNode<K, V> newRoot = new BTreeNode<>(false);
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
        int i = 0;
        while (i < node.keys.size() && node.keys.get(i).compareTo(key) < 0) i++;

        if (node.leaf) {
            // 叶子节点：直接插入
            if (i < node.keys.size() && Objects.equals(node.keys.get(i), key)) {
                node.values.set(i, value);
                return null;
            }
            node.keys.add(i, key);
            node.values.add(i, value);
            if (node.keys.size() < order) return null;
            return doSplit(node);
        }

        // 内部节点：递归插入子节点
        SplitResult<K, V> sub = splitInsert(node.children.get(i), key, value);
        if (sub == null) return null;

        // 子节点分裂，需要将 promotedKey 插入当前节点，并替换 children[i]
        // 策略：将 promotedKey 插入 keys[i]，children[i] 替换为 sub.left 和 sub.right
        // 这样 keys 增加 1，children 增加 1，不变量 children = keys + 1 保持
        node.keys.add(i, sub.promotedKey);
        node.values.add(i, sub.promotedValue);
        node.children.remove(i);
        node.children.add(i, sub.left);
        node.children.add(i + 1, sub.right);

        if (node.keys.size() < order) return null;
        return doSplit(node);
    }

    /**
     * 分裂节点：中间键提升，左右两半分别保留。
     * node 原地修改为左半，返回右半和提升的键。
     */
    private SplitResult doSplit(BTreeNode<K, V> node) {
        int n = node.keys.size();
        int mid = n / 2; // 左半 keys[0..mid-1]，右半 keys[mid+1..n-1]
        K promoteKey = node.keys.get(mid);
        V promoteValue = node.values.get(mid);

        BTreeNode<K, V> right = new BTreeNode<>(node.leaf);
        // 拷贝右半 keys
        for (int j = mid + 1; j < n; j++) {
            right.keys.add(node.keys.get(j));
            right.values.add(node.values.get(j));
        }
        // 拷贝右半 children：right 需要 children[mid+1..n]（共 n-mid 个）
        if (!node.leaf) {
            for (int j = mid + 1; j <= n; j++) {
                right.children.add(node.children.get(j));
            }
            // 左半保留 children[0..mid]（共 mid+1 个），截断 mid+1..n
            node.children.subList(mid + 1, n + 1).clear();
        }
        // 左半保留 keys[0..mid-1]，截断 mid..n-1
        node.keys.subList(mid, n).clear();
        node.values.subList(mid, n).clear();

        return new SplitResult(promoteKey, promoteValue, node, right);
    }

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

    @Override
    public Optional<V> remove(K key) {
        if (key == null) return Optional.empty();
        Optional<V> oldValue = get(key);
        if (oldValue.isEmpty()) return Optional.empty();
        delete(root, key);
        if (root.leaf && root.keys.isEmpty()) root = new BTreeNode<>(true);
        size--;
        return oldValue;
    }

    private void delete(BTreeNode<K, V> node, K key) {
        int i = 0;
        while (i < node.keys.size() && node.keys.get(i).compareTo(key) < 0) i++;
        if (node.leaf) {
            if (i < node.keys.size() && Objects.equals(node.keys.get(i), key)) {
                node.keys.remove(i);
                node.values.remove(i);
            }
            return;
        }
        if (i < node.keys.size() && Objects.equals(node.keys.get(i), key)) {
            if (!node.children.get(i).keys.isEmpty()) {
                K pred = findPredecessor(node.children.get(i));
                node.keys.set(i, pred);
                delete(node.children.get(i), pred);
            } else {
                K succ = findSuccessor(node.children.get(i + 1));
                node.keys.set(i, succ);
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
    public void clear() { root = new BTreeNode<>(true); size = 0; }
    @Override
    public TreeNode<K, V> toBinaryTree() { return BinaryTreeConverter.bTreeToBinary(this); }

    BTreeNode<K, V> getRoot() { return root; }
}
