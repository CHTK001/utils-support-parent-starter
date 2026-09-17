package com.chua.common.support.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * B+ 树实现（优化版）。
 *
 * <p>所有数据存储在叶子节点，内部节点仅作为索引；叶子节点通过链表串联，
 * 范围查询无需回溯，适合数据库索引与内存缓存场景。</p>
 *
 * @param <K> 键类型，须实现 {@link Comparable}
 * @param <V> 值类型
 * @author CH
 * @since 4.0.0.42
*/
public class BPlusTree<K extends Comparable<K>, V> implements TreeEngine<K, V> {

    private final int maxKeys; // 最大键
    private final int maxChildren; // 最大children
    private BPlusTreeNode<K, V> root; // 根
    private int size; // 大小

    /**
    * bplus树。
    * @param order 订单
    */
    public BPlusTree(int order) {
        if (order < 3) {
            throw new IllegalArgumentException("B+ tree order must be >= 3, got: " + order);
        }
        this.maxKeys = order - 1;
        this.maxChildren = order;
        this.root = new BPlusTreeNode<>(true, order * 4);
        this.size = 0;
    }

    @Override
    public String type() { return "B_PLUS_TREE"; }

    // ==================== get ====================

    @Override
    public Optional<V> get(K key) {
        if (key == null) {
            return Optional.empty();
        }
        BPlusTreeNode<K, V> node = root;
        while (true) {
            List<K> keys = node.keys;
            int i = binarySearch(keys, key);
            if (node.leaf) {
                return (i >= 0 && Objects.equals(keys.get(i), key))
                    ? Optional.of(node.values.get(i)) : Optional.empty();
            }
            // B+ 树内部节点的键是其左侧子树的最大键
 // 命中时向 子[i] 查找，未命中时向 子[-i-1] 查找
            node = node.children.get(i >= 0 ? i : -i - 1);
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
        int estimatedSize = Math.min(size, (size / maxKeys) * 2);
        List<Map.Entry<K, V>> result = new ArrayList<>(estimatedSize);
        locateLeaf(root, from, to, result);
        return result;
    }

    /**
    * locateleaf。
    * @param node 节点
    * @param from 从
    * @param to 转为
    * @param result 结果
    */
    private void locateLeaf(BPlusTreeNode<K, V> node, K from, K to, List<Map.Entry<K, V>> result) {
        if (node.leaf) {
            List<K> keys = node.keys;
            List<V> values = node.values;
            int n = keys.size();
            for (int i = 0; i < n; i++) {
                K k = keys.get(i);
                if (gte(k, from) && lt(k, to)) {
                    result.add(Map.entry(k, values.get(i)));
                }
            }
            return;
        }
        int i = 0;
        List<K> keys = node.keys;
        int n = keys.size();
        while (i < n && lt(keys.get(i), from)) {
            i++;
        }
        List<BPlusTreeNode<K, V>> children = node.children;
        for (int j = i; j <= n; j++) {
            if (j > 0 && !lt(keys.get(j - 1), to)) {
                break;
            }
            if (j < children.size()) {
                locateLeaf(children.get(j), from, to, result);
            }
        }
    }

    /**
    * 键 >= bound，bound 为 空 表示 -∞（恒真）。
    * @param key 键
    * @param bound bound
    * @return gte的结果
    */
    private boolean gte(K key, K bound) {
        return bound == null || key.compareTo(bound) >= 0;
    }

    /**
    * 键 < bound，bound 为 空 表示 +∞（恒真）。
    * @param key 键
    * @param bound bound
    * @return lt的结果
    */
    private boolean lt(K key, K bound) {
        return bound == null || key.compareTo(bound) < 0;
    }

    // ==================== put ====================

    @Override
    public Optional<V> put(K key, V value) {
        if (key == null) {
            return Optional.empty();
        }
        NodeUpdate<K, V> update = insert(root, key, value);
        if (update.needsSplit) {
            BPlusTreeNode<K, V> newRoot = new BPlusTreeNode<>(false, maxChildren);
            newRoot.keys.add(update.promotedKey);
            newRoot.children.add(root);
            newRoot.children.add(update.rightChild);
            root = newRoot;
        }
        if (update.oldValue == null) {
            size++;
        }
        return update.oldValue != null ? Optional.of(update.oldValue) : Optional.empty();
    }

    /**
    * 通用插入：逐层二分定位叶子，追加键值对，处理分裂
    *
    * @param node 节点
    * @param key 键
    * @param value 值
    * @return 插入的结果
    */
    private NodeUpdate<K, V> insert(BPlusTreeNode<K, V> node, K key, V value) {
        List<K> keys = node.keys;
        int i = binarySearch(keys, key);
        if (i >= 0) {
            // 键已存在，更新值
            V old = node.values.get(i);
            node.values.set(i, value);
            return NodeUpdate.noSplit(old);
        }
        i = -i - 1;
        if (node.leaf) {
            keys.add(i, key);
            node.values.add(i, value);
            if (keys.size() <= maxKeys) {
                return NodeUpdate.noSplit(null);
            }
            return splitLeaf(node);
        }
        List<BPlusTreeNode<K, V>> children = node.children;
        NodeUpdate<K, V> sub = insert(children.get(i), key, value);
        if (!sub.needsSplit) {
            return sub;
        }
        if (i < node.keys.size()) {
            node.keys.set(i, sub.promotedKey);
        } else {
            node.keys.add(sub.promotedKey);
        }
        node.children.add(i + 1, sub.rightChild);
        if (node.keys.size() <= maxKeys) {
            return NodeUpdate.noSplit(null);
        }
        return splitInternal(node);
    }

    /**
    * 分割leaf。
    * @param node 节点
    * @return 分割leaf的结果
    */
    private NodeUpdate<K, V> splitLeaf(BPlusTreeNode<K, V> node) {
        int n = node.keys.size();
        int mid = n / 2;
        K promoteKey = node.keys.get(mid - 1);
        BPlusTreeNode<K, V> right = new BPlusTreeNode<>(true, n - mid);
        right.next = node.next;
        for (int j = mid; j < n; j++) {
            right.keys.add(node.keys.get(j));
            right.values.add(node.values.get(j));
        }
        node.keys.subList(mid, n).clear();
        node.values.subList(mid, n).clear();
        return NodeUpdate.split(promoteKey, right);
    }

    /**
    * 分割内部。
    * @param node 节点
    * @return 分割内部的结果
    */
    private NodeUpdate<K, V> splitInternal(BPlusTreeNode<K, V> node) {
        int n = node.keys.size();
        int mid = n / 2;
        K promoteKey = node.keys.get(mid);
        BPlusTreeNode<K, V> right = new BPlusTreeNode<>(false, n - mid);
        for (int j = mid + 1; j < n; j++) {
            right.keys.add(node.keys.get(j));
        }
        for (int j = mid + 1; j <= n; j++) {
            right.children.add(node.children.get(j));
        }
        node.keys.subList(mid, n).clear();
        node.children.subList(mid + 1, n + 1).clear();
        return NodeUpdate.split(promoteKey, right);
    }

    // ==================== remove ====================
    /**
    * 节点更新类。
    *
    * @author CH
    * @since 4.0.0
    * @param oldVal 旧val
    * @return no分割的结果
    * @param key 键
    */

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
                root = new BPlusTreeNode<>(true, maxChildren);
            }
        }
        size--;
        return oldValue;
    /**
    * 删除。
    * @param node 节点
    * @param key 键
    */
    }

    private void delete(BPlusTreeNode<K, V> node, K key) {
        int i = 0;
        int n = node.keys.size();
        while (i < n && node.keys.get(i).compareTo(key) < 0) {
            i++;
        }
        if (node.leaf) {
            if (i < n && Objects.equals(node.keys.get(i), key)) {
                node.keys.remove(i);
                node.values.remove(i);
            }
            return;
        }
        delete(node.children.get(i), key);
        if (i < n && Objects.equals(node.keys.get(i), key)) {
            node.keys.remove(i);
            node.children.remove(i);
            if (!node.children.isEmpty()) {
                node.keys.add(i, node.children.get(i).keys.getFirst());
            }
        }
    }

    @Override
    public int size() { return size; }
    @Override
    public boolean isEmpty() { return size == 0; }
    @Override
    public void clear() { root = new BPlusTreeNode<>(true, maxChildren); size = 0; }
    @Override
    public TreeNode<K, V> toBinaryTree() { return BinaryTreeConverter.bPlusToBinary(this); }
    @Override
    public String toString() { return "BPlusTree{maxKeys=" + maxKeys + ", size=" + size + "}"; }

    /**
    * 全部entries。
    * @return 全部entries的结果
    * @author CH
    * @since 4.0.0
    */
    BPlusTreeNode<K, V> getRoot() { return root; }

    /**
    * 全部entries。
    * @return 全部entries的结果
    * @author CH
    * @since 4.0.0
    */
    public List<Map.Entry<K, V>> allEntries() {
        List<Map.Entry<K, V>> result = new ArrayList<>(size);
        BPlusTreeNode<K, V> cur = root;
        while (cur != null && !cur.leaf) {
            cur = cur.children.getFirst();
        }
        while (cur != null) {
            for (int i = 0; i < cur.keys.size(); i++) {
                result.add(Map.entry(cur.keys.get(i), cur.values.get(i)));
            }
            cur = cur.next;
        }
        return result;
    }

    private static class NodeUpdate<K, V> {
        final boolean needsSplit;
        final K promotedKey;
        final BPlusTreeNode<K, V> rightChild;
        /**
        * 节点更新。
        * @param needsSplit needs分割
        * @param promotedKey promoted键
        * @param rightChild right子
        * @param oldValue 旧值
        * @param oldVal 旧val
        * @return no分割的结果
        */
        final V oldValue;

        /**
        * 节点更新。
        * @param needsSplit needs分割
        * @param promotedKey promoted键
        * @param rightChild right子
        * @param oldValue 旧值
        */
        private NodeUpdate(boolean needsSplit, K promotedKey, BPlusTreeNode<K, V> rightChild, V oldValue) {
            this.needsSplit = needsSplit;
            this.promotedKey = promotedKey;
            this.rightChild = rightChild;
            this.oldValue = oldValue;
        }

        static <K, V> NodeUpdate<K, V> noSplit(V oldVal) { return new NodeUpdate<>(false, null, null, oldVal); }
        /**
        * 分割。
        * @param k k
        * @param r r
        * @return 分割的结果
        */
        static <K, V> NodeUpdate<K, V> split(K k, BPlusTreeNode<K, V> r) { return new NodeUpdate<>(true, k, r, null); }
    }
}
