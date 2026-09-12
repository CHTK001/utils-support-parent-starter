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

    private final int order; // 订单
    BTreeNode<K, V> root; // 根
    private int size; // 大小

    /**
    * b树。
    * @param order 订单
     */
    public BTree(int order) {
        if (order < 3) {
            throw new IllegalArgumentException("B tree order must be >= 3, got: " + order);
        }
        this.order = order;
        this.root = new BTreeNode<>(true, order * 4);
        this.size = 0;
    }

    @Override
    public String type() { return "B_TREE"; }

    // ==================== get ====================

    @Override
    public Optional<V> get(K key) {
        if (key == null) {
            return Optional.empty();
        }
        BTreeNode<K, V> node = root;
        while (true) {
            List<K> keys = node.keys;
            int i = binarySearch(keys, key);
            if (i >= 0 && Objects.equals(keys.get(i), key)) {
                return Optional.of(node.values.get(i));
            }
            if (node.leaf) {
                return Optional.empty();
            }
            node = node.children.get(-i - 1);
        }
    }

    @Override
    public boolean containsKey(K key) {
        return get(key).isPresent();
    }

    /**
    * 二分查找：命中返回索引，未命中返回 -(插入点+1)
    *
    * @param keys 键
    * @param key 键
    * @return binary搜索的结果
     */
    private static <K extends Comparable<K>> int binarySearch(List<K> keys, K key) {
        int lo = 0, hi = keys.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = keys.get(mid).compareTo(key);
            if (c < 0) {
                lo = mid + 1;
            }
            else if (c > 0) {
                hi = mid - 1;
            }
            else {
                return mid;
            }
        }
        return -(lo + 1);
    }

    // ==================== range ====================

    @Override
    public List<Map.Entry<K, V>> range(K from, K to) {
        if (from == null || to == null) {
            return Collections.emptyList();
        }
        int estimatedSize = Math.min(size, (size / Math.max(order / 2, 1)) * 2);
        List<Map.Entry<K, V>> result = new ArrayList<>(estimatedSize);
        collectRange(root, from, to, result);
        return result;
    }

    /**
    * collect范围。
    * @param node 节点
    * @param from 从
    * @param to 转为
    * @param result 结果
     */
    private void collectRange(BTreeNode<K, V> node, K from, K to, List<Map.Entry<K, V>> result) {
        List<K> keys = node.keys;
        List<V> values = node.values;
        int n = keys.size();
        for (int i = 0; i < n; i++) {
            K k = keys.get(i);
            if (k.compareTo(from) < 0) {
                continue;
            }
            if (k.compareTo(to) >= 0) {
                break;
            }
            result.add(Map.entry(k, values.get(i)));
        }
        if (node.leaf) {
            return;
        }
        for (BTreeNode<K, V> child : node.children) {
            collectRange(child, from, to, result);
        }
    }

    // ==================== put ====================

    @Override
    public Optional<V> put(K key, V value) {
        if (key == null) {
            return Optional.empty();
        }
        SplitResult<K, V> split = splitInsert(root, key, value);
        if (split != null) {
            BTreeNode<K, V> newRoot = new BTreeNode<>(false, order * 2);
            newRoot.keys.add(split.promotedKey);
            newRoot.values.add(split.promotedValue);
            newRoot.children.add(split.left);
            newRoot.children.add(split.right);
            root = newRoot;
        }
        size++;
        return Optional.empty();
    }

    /**
    * 分割插入。
    * @param node 节点
    * @param key 键
    * @param value 值
    * @return 分割插入的结果
     */
    private SplitResult<K, V> splitInsert(BTreeNode<K, V> node, K key, V value) {
        List<K> keys = node.keys;
        List<V> values = node.values;
        int i = binarySearch(keys, key);
        if (i >= 0) {
            values.set(i, value);
            return null;
        }
        i = -i - 1;

        if (node.leaf) {
            keys.add(i, key);
            values.add(i, value);
            if (keys.size() < order) {
                return null;
            }
            return doSplit(node);
        }

        List<BTreeNode<K, V>> children = node.children;
        SplitResult<K, V> sub = splitInsert(children.get(i), key, value);
        if (sub == null) {
            return null;
        }

        keys.add(i, sub.promotedKey);
        values.add(i, sub.promotedValue);
        children.remove(i);
        children.add(i, sub.left);
        children.add(i + 1, sub.right);

        if (keys.size() < order) {
            return null;
        }
        return doSplit(node);
    }

    /**
    * 执行分割。
    * @param node 节点
    * @return 执行分割的结果
     */
    private SplitResult<K, V> doSplit(BTreeNode<K, V> node) {
        List<K> keys = node.keys;
        List<V> values = node.values;
        int n = keys.size();
        int mid = n / 2;
        K promoteKey = keys.get(mid);
        V promoteValue = values.get(mid);

        BTreeNode<K, V> right = new BTreeNode<>(node.leaf, n - mid);
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
        if (key == null) {
            return Optional.empty();
        }
        Optional<V> oldValue = get(key);
        if (oldValue.isEmpty()) {
            return Optional.empty();
        }
        delete(root, key);
        if (root.leaf && root.keys.isEmpty()) {
            root = new BTreeNode<>(true, order);
        }
        size--;
        return oldValue;
    }

    /**
    * 删除。
    * @param node 节点
    * @param key 键
     */
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
        int ins = -i - 1;
        delete(node.children.get(ins), key);
    }

    /**
    * findpredecessor。
    * @param node 节点
    * @return findPredecessor的结果
     */
    private K findPredecessor(BTreeNode<K, V> node) {
        while (!node.leaf) {
            node = node.children.get(node.keys.size());
        }
        return node.keys.get(node.keys.size() - 1);
    }

    /**
    * findsuccessor。
    * @param node 节点
    * @return findSuccessor的结果
     */
    private K findSuccessor(BTreeNode<K, V> node) {
        while (!node.leaf) {
            node = node.children.get(0);
        }
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
    /**
    * 分割结果类。
    *
    * @author CH
    * @since 4.0.0
     */

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
