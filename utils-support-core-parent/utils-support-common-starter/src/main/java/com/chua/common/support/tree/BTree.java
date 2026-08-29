package com.chua.common.support.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
        this.root = new BTreeNode<>(true, order * 4);
        this.size = 0;
    }

    @Override
    public String type() { return "B_TREE"; }

    // ==================== get ====================

    @Override
    public Optional<V> get(K key) {
        if (key == null) return Optional.empty();
        // 迭代查找，避免递归栈开销；内部使用二分搜索
        BTreeNode<K, V> node = root;
        while (true) {
            List<K> keys = node.keys;
            int i = binarySearch(keys, key);
            if (i >= 0 && Objects.equals(keys.get(i), key)) {
                return Optional.of(node.values.get(i));
            }
            if (node.leaf) return Optional.empty();
            node = node.children.get(-i - 1);
        }
    }

    @Override
    public boolean containsKey(K key) {
        return get(key).isPresent();
    }

    /** 二分查找：命中返回索引，未命中返回 -(插入点+1) */
    private static <K extends Comparable<K>> int binarySearch(List<K> keys, K key) {
        int lo = 0, hi = keys.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = keys.get(mid).compareTo(key);
            if (c < 0) lo = mid + 1;
            else if (c > 0) hi = mid - 1;
            else return mid;
        }
        return -(lo + 1);
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
        List<K> keys = node.keys;
        List<V> values = node.values;
        int n = keys.size();
        // 收集当前节点在 [from, to) 范围内的键
        for (int i = 0; i < n; i++) {
            K k = keys.get(i);
            if (k.compareTo(from) < 0) continue;
            if (k.compareTo(to) >= 0) break;
            result.add(Map.entry(k, values.get(i)));
        }
        if (node.leaf) return;
        List<BTreeNode<K, V>> children = node.children;
        // 只访问可能与 [from, to) 有交集的子树
        // child[i] 包含所有 < keys[i] 的键，child[i+1] 包含所有 > keys[i] 的键
        // firstChild: 第一个 keys[i] >= from 的位置（该位置的子树可能含 >= from 的键）
        // lastChild:  第一个 keys[i] >= to 的位置（该位置及之后，子树全 >= to，无需访问）
        int firstChild = binarySearchGE(keys, from);
        int lastChild = binarySearchGE(keys, to);
        // 边界修正：当 lastChild==0 时（所有 keys >= to），仍需检查 child[0]
        if (lastChild == 0) lastChild = 1;
        for (int i = firstChild; i < lastChild && i < children.size(); i++) {
            collectRange(children.get(i), from, to, result);
        }
    }

    /** 二分找第一个 >= key 的位置 */
    private static <K extends Comparable<K>> int binarySearchGE(List<K> keys, K key) {
        int lo = 0, hi = keys.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (keys.get(mid).compareTo(key) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** 二分找第一个 > key 的位置 */
    private static <K extends Comparable<K>> int binarySearchGT(List<K> keys, K key) {
        int lo = 0, hi = keys.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (keys.get(mid).compareTo(key) <= 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
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

    private SplitResult<K, V> splitInsert(BTreeNode<K, V> node, K key, V value) {
        List<K> keys = node.keys;
        List<V> values = node.values;
        // 用二分查找确定插入位置
        int i = binarySearch(keys, key);
        if (i >= 0) {
            // 键已存在，直接更新
            values.set(i, value);
            return null;
        }
        i = -i - 1; // 转换插入点

        if (node.leaf) {
            keys.add(i, key);
            values.add(i, value);
            if (keys.size() < order) return null;
            return doSplit(node);
        }

        // 内部节点：递归插入子节点
        List<BTreeNode<K, V>> children = node.children;
        SplitResult<K, V> sub = splitInsert(children.get(i), key, value);
        if (sub == null) return null;

        // 子节点分裂，将 promotedKey 插入 keys[i]，children[i] 替换为 left 和 right
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
    private SplitResult<K, V> doSplit(BTreeNode<K, V> node) {
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

        return new SplitResult<>(promoteKey, promoteValue, node, right);
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
        int i = binarySearch(keys, key);
        if (node.leaf) {
            if (i >= 0 && Objects.equals(keys.get(i), key)) {
                keys.remove(i);
                node.values.remove(i);
            }
            return;
        }
        if (i >= 0 && Objects.equals(keys.get(i), key)) {
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
        // key 不在当前节点，在子树中
        int ins = -i - 1;
        delete(node.children.get(ins), key);
    }

    private K findPredecessor(BTreeNode<K, V> node) {
        while (!node.leaf) node = node.children.get(node.keys.size());
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
