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

    /** 每个节点最多存储的键数量（即阶数减 1） */
    private final int maxKeys;
    /** 每个内部节点最多拥有的子节点数量（即阶数） */
    private final int maxChildren;
    private BPlusTreeNode<K, V> root;
    /** 当前存储条目数量 */
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
        this.maxKeys = order - 1;
        this.maxChildren = order;
        this.root = new BPlusTreeNode<>(true);
        this.root.setInitialCapacity(order);
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
            if (j < node.children.size()) {
                locateLeaf(node.children.get(j), from, to, result);
            }
        }
    }

    // ==================== put ====================

    @Override
    public Optional<V> put(K key, V value) {
        if (key == null) {
            return Optional.empty();
        }
        NodeUpdate<K, V> update = insert(root, key, value);
        if (update.needsSplit) {
            BPlusTreeNode<K, V> newRoot = new BPlusTreeNode<>(false);
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

    private NodeUpdate<K, V> insert(BPlusTreeNode<K, V> node, K key, V value) {
        int i = 0;
        while (i < node.keys.size() && node.keys.get(i).compareTo(key) < 0) {
            i++;
        }
        if (node.leaf) {
            if (i < node.keys.size() && Objects.equals(node.keys.get(i), key)) {
                V old = node.values.get(i);
                node.values.set(i, value);
                return new NodeUpdate<>(false, null, null, old);
            }
            // 直接原地修改，避免不必要的 ArrayList 拷贝
            node.keys.add(i, key);
            node.values.add(i, value);
            if (node.keys.size() <= maxKeys) {
                return new NodeUpdate<>(false, null, null, null);
            }
            return splitLeaf(node);
        }

        NodeUpdate<K, V> sub = insert(node.children.get(i), key, value);
        if (!sub.needsSplit) {
            return sub;
        }
        // 直接原地修改，避免不必要的 ArrayList 拷贝
        // 子节点分裂后，更新 keys[i] 或追加 promotedKey，然后插入 rightChild
        if (i < node.keys.size()) {
            node.keys.set(i, sub.promotedKey);
        } else {
            node.keys.add(sub.promotedKey);
        }
        node.children.add(i + 1, sub.rightChild);
        if (node.keys.size() <= maxKeys) {
            return new NodeUpdate<>(false, null, null, null);
        }
        return splitInternal(node);
    }

    private NodeUpdate<K, V> splitLeaf(BPlusTreeNode<K, V> node) {
        int n = node.keys.size();
        int mid = n / 2; // 左半 keys[0..mid-1]，右半 keys[mid..n-1]
        K promoteKey = node.keys.get(mid - 1);
        BPlusTreeNode<K, V> right = new BPlusTreeNode<>(true);
        right.next = node.next;
        // 拷贝右半部分（keys[mid..n-1]）
        for (int j = mid; j < n; j++) {
            right.keys.add(node.keys.get(j));
            right.values.add(node.values.get(j));
        }
        // 左半保留 keys[0..mid-1]（含 promoted key），不做截断
        return new NodeUpdate<>(true, promoteKey, right, null);
    }

    private NodeUpdate<K, V> splitInternal(BPlusTreeNode<K, V> node) {
        int n = node.keys.size();
        int mid = n / 2;
        K promoteKey = node.keys.get(mid);
        BPlusTreeNode<K, V> right = new BPlusTreeNode<>(false);
        // left 保留 keys[0..mid-1]，children[0..mid]（mid+1 个 child）
        // right 获得 keys[mid+1..n-1]，children[mid+1..n-1]（n-mid 个 child）
        // 验证：left: children=mid+1, keys=mid → children=keys+1 ✓
        //       right: children=n-mid, keys=n-mid-1 → children=keys+1 ✓
        for (int j = mid + 1; j < n; j++) {
            right.keys.add(node.keys.get(j));
        }
        for (int j = mid + 1; j <= n; j++) {
            right.children.add(node.children.get(j));
        }
        node.keys.subList(mid, n).clear();
        node.children.subList(mid + 1, n + 1).clear();
        return new NodeUpdate<>(true, promoteKey, right, null);
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

    @Override
    public String toString() {
        return "BPlusTree{maxKeys=" + maxKeys + ", size=" + size + "}";
    }

    /** 供 BinaryTreeConverter 使用，获取根节点。 */
    BPlusTreeNode<K, V> getRoot() { return root; }

    /**
     * 收集树中全部有序条目（沿叶子链表遍历）。
     * <p>用于外部扫描所有索引条目，如向量存储的冷数据检索。</p>
     */
    public List<Map.Entry<K, V>> allEntries() {
        List<Map.Entry<K, V>> result = new ArrayList<>();
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
     */
    private static class NodeUpdate<K, V> {
        final boolean needsSplit;
        final K promotedKey;
        final BPlusTreeNode<K, V> rightChild;
        final V oldValue;

        NodeUpdate(boolean needsSplit, K promotedKey, BPlusTreeNode<K, V> rightChild, V oldValue) {
            this.needsSplit = needsSplit;
            this.promotedKey = promotedKey;
            this.rightChild = rightChild;
            this.oldValue = oldValue;
        }
    }
}
