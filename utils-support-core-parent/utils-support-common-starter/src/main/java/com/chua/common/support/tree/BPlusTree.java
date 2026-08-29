package com.chua.common.support.tree;

import java.util.ArrayList;
import java.util.Collections;
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

    private final int maxKeys;
    private final int maxChildren;
    private BPlusTreeNode<K, V> root;
    private int size;
    /** 最后一次插入的键，用于顺序插入加速 */
    private K lastKey;
    /** 最后插入的节点引用，用于顺序插入时直接追加到右端 */
    private BPlusTreeNode<K, V> tail;

    public BPlusTree(int order) {
        if (order < 3) throw new IllegalArgumentException("B+ tree order must be >= 3, got: " + order);
        this.maxKeys = order - 1;
        this.maxChildren = order;
        this.root = new BPlusTreeNode<>(true, order * 4);
        this.tail = root;
        this.size = 0;
        this.lastKey = null;
    }

    @Override
    public String type() { return "B_PLUS_TREE"; }

    // ==================== get ====================

    @Override
    public Optional<V> get(K key) {
        if (key == null) return Optional.empty();
        BPlusTreeNode<K, V> node = root;
        while (true) {
            List<K> keys = node.keys;
            int i = 0;
            int n = keys.size();
            while (i < n && keys.get(i).compareTo(key) < 0) i++;
            if (node.leaf) {
                return (i < n && Objects.equals(keys.get(i), key))
                    ? Optional.of(node.values.get(i)) : Optional.empty();
            }
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
        int estimatedSize = Math.min(size, (size / maxKeys) * 2);
        List<Map.Entry<K, V>> result = new ArrayList<>(estimatedSize);
        locateLeaf(root, from, to, result);
        return result;
    }

    private void locateLeaf(BPlusTreeNode<K, V> node, K from, K to, List<Map.Entry<K, V>> result) {
        if (node.leaf) {
            List<K> keys = node.keys;
            List<V> values = node.values;
            int n = keys.size();
            for (int i = 0; i < n; i++) {
                K k = keys.get(i);
                if (k.compareTo(from) >= 0 && k.compareTo(to) < 0) {
                    result.add(Map.entry(k, values.get(i)));
                }
            }
            return;
        }
        int i = 0;
        List<K> keys = node.keys;
        int n = keys.size();
        while (i < n && keys.get(i).compareTo(from) < 0) i++;
        List<BPlusTreeNode<K, V>> children = node.children;
        for (int j = i; j <= n; j++) {
            if (j > 0 && keys.get(j - 1).compareTo(to) >= 0) break;
            if (j < children.size()) locateLeaf(children.get(j), from, to, result);
        }
    }

    // ==================== put ====================

    @Override
    public Optional<V> put(K key, V value) {
        if (key == null) return Optional.empty();
        // 顺序插入加速：若新键大于最后插入的键且 tail 未饱和，直接追加到 tail
        if (lastKey != null && key.compareTo(lastKey) > 0 && tail.keys.size() < maxKeys) {
            tail.keys.add(key);
            tail.values.add(value);
            size++;
            lastKey = key;
            return NO_UPDATE;
        }
        NodeUpdate<K, V> update = insert(root, key, value);
        if (update.needsSplit) {
            BPlusTreeNode<K, V> newRoot = new BPlusTreeNode<>(false, maxChildren);
            newRoot.keys.add(update.promotedKey);
            newRoot.children.add(root);
            newRoot.children.add(update.rightChild);
            root = newRoot;
            // 分裂后重建 tail 引用
            rebuildTail();
        }
        size++;
        lastKey = key;
        return NO_UPDATE;
    }

    /** 分裂后重新定位 tail 为最右叶子节点 */
    private void rebuildTail() {
        BPlusTreeNode<K, V> cur = root;
        while (!cur.leaf) cur = cur.children.get(cur.children.size() - 1);
        tail = cur;
    }

    /** 通用插入（随机键时使用） */
    private NodeUpdate<K, V> insert(BPlusTreeNode<K, V> node, K key, V value) {
        List<K> keys = node.keys;
        int i = 0;
        int n = keys.size();
        while (i < n && keys.get(i).compareTo(key) < 0) i++;
        if (node.leaf) {
            if (i < n && Objects.equals(keys.get(i), key)) {
                node.values.set(i, value);
            return noUpdate();
            }
            keys.add(i, key);
            node.values.add(i, value);
            if (keys.size() <= maxKeys) return NO_UPDATE;
            return splitLeaf(node);
        }
        List<BPlusTreeNode<K, V>> children = node.children;
        NodeUpdate<K, V> sub = insert(children.get(i), key, value);
        if (!sub.needsSplit) return sub;
        if (i < node.keys.size()) {
            node.keys.set(i, sub.promotedKey);
        } else {
            node.keys.add(sub.promotedKey);
        }
        node.children.add(i + 1, sub.rightChild);
        if (node.keys.size() <= maxKeys) return NO_UPDATE;
        return splitInternal(node);
    }

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

    private NodeUpdate<K, V> splitInternal(BPlusTreeNode<K, V> node) {
        int n = node.keys.size();
        int mid = n / 2;
        K promoteKey = node.keys.get(mid);
        BPlusTreeNode<K, V> right = new BPlusTreeNode<>(false, n - mid);
        for (int j = mid + 1; j < n; j++) right.keys.add(node.keys.get(j));
        for (int j = mid + 1; j <= n; j++) right.children.add(node.children.get(j));
        node.keys.subList(mid, n).clear();
        node.children.subList(mid + 1, n + 1).clear();
        return NodeUpdate.split(promoteKey, right);
    }

    // ==================== remove ====================

    @Override
    public Optional<V> remove(K key) {
        if (key == null) return Optional.empty();
        Optional<V> oldValue = get(key);
        if (oldValue.isEmpty()) return Optional.empty();
        delete(root, key);
        if (root.leaf && root.keys.isEmpty()) {
            root = root.next;
            if (root == null) root = new BPlusTreeNode<>(true, maxChildren);
        }
        size--;
        return oldValue;
    }

    private void delete(BPlusTreeNode<K, V> node, K key) {
        int i = 0;
        int n = node.keys.size();
        while (i < n && node.keys.get(i).compareTo(key) < 0) i++;
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
                node.keys.add(i, node.children.get(i).keys.get(0));
            }
        }
    }

    @Override
    public int size() { return size; }
    @Override
    public boolean isEmpty() { return size == 0; }
    @Override
    public void clear() { root = new BPlusTreeNode<>(true, maxChildren); tail = root; size = 0; lastKey = null; }
    @Override
    public TreeNode<K, V> toBinaryTree() { return BinaryTreeConverter.bPlusToBinary(this); }
    @Override
    public String toString() { return "BPlusTree{maxKeys=" + maxKeys + ", size=" + size + "}"; }

    BPlusTreeNode<K, V> getRoot() { return root; }
    BPlusTreeNode<K, V> getTail() { return tail; }

    public List<Map.Entry<K, V>> allEntries() {
        List<Map.Entry<K, V>> result = new ArrayList<>(size);
        BPlusTreeNode<K, V> cur = root;
        while (cur != null && !cur.leaf) cur = cur.children.get(0);
        while (cur != null) {
            for (int i = 0; i < cur.keys.size(); i++) {
                result.add(Map.entry(cur.keys.get(i), cur.values.get(i)));
            }
            cur = cur.next;
        }
        return result;
    }

    /**
     * 节点更新结果。
     * 使用静态共享实例避免高频分配。
     */
    private static class NodeUpdate<K, V> {
        boolean needsSplit;
        final K promotedKey;
        final BPlusTreeNode<K, V> rightChild;

        private NodeUpdate(boolean needsSplit, K promotedKey, BPlusTreeNode<K, V> rightChild) {
            this.needsSplit = needsSplit;
            this.promotedKey = promotedKey;
            this.rightChild = rightChild;
        }

        static <K, V> NodeUpdate<K, V> noSplit() { return new NodeUpdate<>(false, null, null); }
        static <K, V> NodeUpdate<K, V> split(K k, BPlusTreeNode<K, V> r) { return new NodeUpdate<>(true, k, r); }
    }

    @SuppressWarnings("unchecked")
    private static final NodeUpdate<?, ?> NO_UPDATE = NodeUpdate.noSplit();

    @SuppressWarnings("unchecked")
    private static <K, V> NodeUpdate<K, V> noUpdate() { return (NodeUpdate<K, V>) NO_UPDATE; }
}
