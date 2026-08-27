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

    @Override
    public Optional<V> put(K key, V value) {
        if (key == null) {
            return Optional.empty();
        }
        SplitResultInternal<K, V> result = insert(root, key, value);
        if (result.split != null) {
            BTreeNode<K, V> newRoot = new BTreeNode<>(false);
            newRoot.keys.add(result.promotedKey);
            newRoot.values.add(result.promotedValue);
            newRoot.children.add(root);
            newRoot.children.add(result.split);
            root = newRoot;
        }
        if (result.promotedValue == null && get(key).isPresent()) {
            size++;
        }
        return result.promotedValue == null ? Optional.empty() : Optional.of(result.promotedValue);
    }

    /**
     * 递归插入键值对，返回提升键结果。
     *
     * @param node  当前节点
     * @param key   键
     * @param value 值
     * @return 提升键结果
     */
    private SplitResultInternal<K, V> insert(BTreeNode<K, V> node, K key, V value) {
        int i = 0;
        while (i < node.keys.size() && node.keys.get(i).compareTo(key) < 0) {
            i++;
        }
        if (node.leaf) {
            if (i < node.keys.size() && Objects.equals(node.keys.get(i), key)) {
                V old = node.values.get(i);
                node.values.set(i, value);
                return new SplitResultInternal<>(null, key, old);
            }
            node.keys.add(i, key);
            node.values.add(i, value);
            if (!node.isFull(order)) {
                return new SplitResultInternal<>(null, key, null);
            }
            return splitNode(node);
        }
        SplitResultInternal<K, V> sub = insert(node.children.get(i), key, value);
        if (sub.split == null) {
            return sub;
        }
        node.keys.add(i, sub.promotedKey);
        node.values.add(i, sub.promotedValue);
        node.children.add(i + 1, sub.split);
        if (!node.isFull(order)) {
            return new SplitResultInternal<>(null, key, null);
        }
        return splitNode(node);
    }

    /**
     * 分裂节点：中间键提升，左右两半分别保留。
     *
     * @param node 待分裂节点
     * @return 提升键结果
     */
    private SplitResultInternal<K, V> splitNode(BTreeNode<K, V> node) {
        int mid = node.keys.size() / 2;
        K midKey = node.keys.get(mid);
        V midValue = node.values.get(mid);
        BTreeNode<K, V> right = new BTreeNode<>(node.leaf);
        for (int i = mid + 1; i < node.keys.size(); i++) {
            right.keys.add(node.keys.remove(mid + 1));
            right.values.add(node.values.remove(mid + 1));
            if (!node.leaf) {
                right.children.add(node.children.remove(mid + 1));
            }
        }
        node.keys.remove(mid);
        node.values.remove(mid);
        if (!node.leaf) {
            node.children.remove(mid + 1);
        }
        return new SplitResultInternal<>(right, midKey, midValue);
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
                node.values.set(i, node.values.get(i));
                delete(node.children.get(i), pred);
            } else {
                K succ = findSuccessor(node.children.get(i + 1));
                node.keys.set(i, succ);
                node.values.set(i, node.values.get(i));
                delete(node.children.get(i + 1), succ);
            }
            return;
        }
        delete(node.children.get(i), key);
    }

    /**
     * 查找前驱（左子树最大键）。
     *
     * @param node 左子节点
     * @return 前驱键
     */
    private K findPredecessor(BTreeNode<K, V> node) {
        while (!node.leaf) {
            node = node.children.get(node.keys.size() - 1);
        }
        return node.keys.get(node.keys.size() - 1);
    }

    /**
     * 查找后继（右子树最小键）。
     *
     * @param node 右子节点
     * @return 后继键
     */
    private K findSuccessor(BTreeNode<K, V> node) {
        while (!node.leaf) {
            node = node.children.get(0);
        }
        return node.keys.get(0);
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

    /**
     * 内部提升键结果记录。
     *
     * @param <K>            键类型
     * @param <V>            值类型
     * @param split          分裂产生的右半节点
     * @param promotedKey    提升的键
     * @param promotedValue  提升的键对应的值
     */
    private record SplitResultInternal<K, V>(
            BTreeNode<K, V> split,
            K promotedKey,
            V promotedValue
    ) {
    }

    @Override
    public String toString() {
        return "BTree{order=" + order + ", size=" + size + "}";
    }
}
