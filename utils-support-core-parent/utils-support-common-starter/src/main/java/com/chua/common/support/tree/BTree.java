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
    /** 树根节点 */
    BTreeNode<K, V> root;
    /** 当前存储条目数量 */
    private int size;

    /**
     * 构造 B 树，指定阶数。
     *
     * @param order B 树阶数，建议 100~512
     * @throws IllegalArgumentException 当 order &lt; 3 时
     */
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

    // ==================== get ====================

    @Override
    public Optional<V> get(K key) {
        if (key == null) {
            return Optional.empty();
        }
        return search(root, key);
    }

    @Override
    public boolean containsKey(K key) {
        return get(key).isPresent();
    }

    /**
     * 递归搜索键对应的值。
     *
     * @param node 当前节点
     * @param key  查询键
     * @return 结果包装
     */
    private Optional<V> search(BTreeNode<K, V> node, K key) {
        int i = 0;
        while (i < node.keys.size() && node.keys.get(i).compareTo(key) < 0) {
            i++;
        }
        if (i < node.keys.size() && Objects.equals(node.keys.get(i), key)) {
            return Optional.of(node.values.get(i));
        }
        if (node.leaf) {
            return Optional.empty();
        }
        return search(node.children.get(i), key);
    }

    // ==================== range ====================

    @Override
    public List<Map.Entry<K, V>> range(K from, K to) {
        if (from == null || to == null) {
            return Collections.emptyList();
        }
        List<Map.Entry<K, V>> result = new ArrayList<>();
        collectRange(root, from, to, result);
        return result;
    }

    /**
     * 中序遍历收集区间内所有条目。
     *
     * @param node   当前节点
     * @param from   下界（含）
     * @param to     上界（不含）
     * @param result 结果收集列表
     */
    private void collectRange(BTreeNode<K, V> node, K from, K to, List<Map.Entry<K, V>> result) {
        for (int i = 0; i < node.keys.size(); i++) {
            K k = node.keys.get(i);
            if (k.compareTo(from) >= 0 && k.compareTo(to) < 0) {
                result.add(Map.entry(k, node.values.get(i)));
            }
            if (k.compareTo(to) >= 0 && !node.leaf) {
                return;
            }
            if (!node.leaf) {
                collectRange(node.children.get(i), from, to, result);
            }
        }
        if (!node.leaf) {
            collectRange(node.children.get(node.keys.size()), from, to, result);
        }
    }

    // ==================== put ====================

    /**
     * 分裂节点的内部结果包装。
     */
    private static class NodeUpdate<K, V> {
        final boolean needsSplit;
        final K promotedKey;
        final V promotedValue;
        final BTreeNode<K, V> rightChild;

        NodeUpdate(boolean needsSplit, K promotedKey, V promotedValue, BTreeNode<K, V> rightChild) {
            this.needsSplit = needsSplit;
            this.promotedKey = promotedKey;
            this.promotedValue = promotedValue;
            this.rightChild = rightChild;
        }
    }

    @Override
    public Optional<V> put(K key, V value) {
        if (key == null) {
            return Optional.empty();
        }
        boolean existed = containsKey(key);
        NodeUpdate<K, V> update = insertInternal(root, key, value);
        if (update.needsSplit) {
            BTreeNode<K, V> newRoot = new BTreeNode<>(false);
            newRoot.keys.add(update.promotedKey);
            newRoot.values.add(update.promotedValue);
            newRoot.children.add(root);
            newRoot.children.add(update.rightChild);
            root = newRoot;
        }
        if (!existed) {
            size++;
        }
        return existed ? Optional.of(update.promotedValue) : Optional.empty();
    }

    /**
     * 递归插入键值对（统一处理叶子和内部节点）。
     *
     * @param node  当前节点
     * @param key   键
     * @param value 值
     * @return 插入结果
     */
    private NodeUpdate<K, V> insertInternal(BTreeNode<K, V> node, K key, V value) {
        int i = 0;
        while (i < node.keys.size() && node.keys.get(i).compareTo(key) < 0) {
            i++;
        }
        if (node.leaf) {
            return insertLeaf(node, i, key, value);
        }
        NodeUpdate<K, V> sub = insertInternal(node.children.get(i), key, value);
        if (!sub.needsSplit) {
            return sub;
        }
        // Merge current keys with promoted key and child
        // B树分裂语义：promotedKey 插入 keys[i]（新增分隔键），
        // children[i] 被替换为 split 后的左子树和右子树两个节点。
        // 注意：必须先保存旧子节点引用，因为 splitNode 会原地修改它（变为左半），
        // 如果用 node.children.get(i) 会得到已被修改的左半而非原始节点。
        BTreeNode<K, V> oldChild = node.children.get(i);
        List<K> newKeys = new ArrayList<>(node.keys);
        List<V> newValues = new ArrayList<>(node.values);
        List<BTreeNode<K, V>> newChildren = new ArrayList<>(node.children);
        if (i < newKeys.size()) {
            newKeys.add(i, sub.promotedKey);
            if (sub.promotedValue != null) {
                newValues.add(i, sub.promotedValue);
            } else {
                newValues.add(i, null);
            }
        } else {
            newKeys.add(sub.promotedKey);
            newValues.add(sub.promotedValue);
        }
        // children[i] 被左子树（oldChild，已被 splitNode 原地修改为左半）和右子树取代
        newChildren.remove(i);
        newChildren.add(i, oldChild);
        newChildren.add(i + 1, sub.rightChild);
        if (newKeys.size() <= order - 1) {
            node.keys = newKeys;
            node.values = newValues;
            node.children = newChildren;
            return new NodeUpdate<>(false, key, null, null);
        }
        return splitNode(node);
    }

    /**
     * 叶子节点插入逻辑。
     *
     * @param node  叶子节点
     * @param i     待插入位置
     * @param key   键
     * @param value 值
     * @return 插入结果
     */
    private NodeUpdate<K, V> insertLeaf(BTreeNode<K, V> node, int i, K key, V value) {
        if (i < node.keys.size() && Objects.equals(node.keys.get(i), key)) {
            V old = node.values.get(i);
            node.values.set(i, value);
            return new NodeUpdate<>(false, key, old, null);
        }
        // 直接原地插入，避免不必要的 ArrayList 拷贝
        node.keys.add(i, key);
        node.values.add(i, value);
        if (node.keys.size() <= order - 1) {
            return new NodeUpdate<>(false, key, null, null);
        }
        return splitNode(node);
    }

    /**
     * 分裂节点：中间键提升，左右两半分别保留。
     *
     * @param node 待分裂节点（原地修改为右半部分）
     * @return 分裂结果
     */
    private NodeUpdate<K, V> splitNode(BTreeNode<K, V> node) {
        int n = node.keys.size();
        int mid = n / 2; // 左半 keys[0..mid-1]，右半 keys[mid+1..n-1]
        K midKey = node.keys.get(mid);
        V midValue = node.values.get(mid);
        // 右半节点：keys[mid+1..n-1]，children[mid+1..n]
        // 注意：midKey 提升给父节点，不放入 right
        BTreeNode<K, V> right = new BTreeNode<>(node.leaf);
        for (int j = mid + 1; j < n; j++) {
            right.keys.add(node.keys.get(j));
            right.values.add(node.values.get(j));
            if (!node.leaf) {
                right.children.add(node.children.get(j));
            }
        }
        // 左半节点保留 keys[0..mid-1]，children[0..mid]
        // 左半的 children[mid] 是对应 midKey 的左子树（由父节点在合并时处理）
        node.keys.subList(mid, n).clear();
        node.values.subList(mid, n).clear();
        if (!node.leaf) {
            node.children.subList(mid + 1, n + 1).clear();
        }
        return new NodeUpdate<>(true, midKey, midValue, right);
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
            root = new BTreeNode<>(true);
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
    private void delete(BTreeNode<K, V> node, K key) {
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

    /**
     * 查找前驱（沿左子树向右下走到叶子，取最后一个键）。
     *
     * @param node 左子节点，须非空
     * @return 前驱键（左子树中的最大键）
     */
    private K findPredecessor(BTreeNode<K, V> node) {
        while (!node.leaf) {
            node = node.children.get(node.keys.size() - 1);
        }
        return node.keys.get(node.keys.size() - 1);
    }

    /**
     * 查找后继（沿右子树向左下走到叶子，取第一个键）。
     *
     * @param node 右子节点，须非空
     * @return 后继键（右子树中的最小键）
     */
    private K findSuccessor(BTreeNode<K, V> node) {
        while (!node.leaf) {
            node = node.children.get(0);
        }
        return node.keys.get(0);
    }

    // ==================== helpers ====================

    private static <K> List<K> keysLeft(List<K> keys, int mid) {
        return new ArrayList<>(keys.subList(0, mid));
    }

    private static <K> List<K> keysRight(List<K> keys, int mid) {
        return new ArrayList<>(keys.subList(mid + 1, keys.size()));
    }

    private static <V> List<V> valuesLeft(List<V> values, int mid) {
        return new ArrayList<>(values.subList(0, mid));
    }

    private static <V> List<V> valuesRight(List<V> values, int mid) {
        return new ArrayList<>(values.subList(mid + 1, values.size()));
    }

    private static <T> List<T> childrenLeft(List<T> children, int mid) {
        return new ArrayList<>(children.subList(0, mid + 1));
    }

    private static <T> List<T> childrenRight(List<T> children, int mid) {
        return new ArrayList<>(children.subList(mid + 1, children.size()));
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
        root = new BTreeNode<>(true);
        size = 0;
    }

    @Override
    public TreeNode<K, V> toBinaryTree() {
        return BinaryTreeConverter.bTreeToBinary(this);
    }

    @Override
    public String toString() {
        return "BTree{order=" + order + ", size=" + size + "}";
    }

    /** 供 BinaryTreeConverter 使用，获取根节点。 */
    BTreeNode<K, V> getRoot() { return root; }
}
