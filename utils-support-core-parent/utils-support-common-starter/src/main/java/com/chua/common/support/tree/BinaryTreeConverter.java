package com.chua.common.support.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * B+/B 树与二叉树之间双向转换工具。
 *
 * <p>转换策略采用前序展开：将有序遍历结果依次链接为右斜二叉树，
 * 恢复时通过右链遍历重建 B+ 树或 B 树。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class BinaryTreeConverter {

    /**
     * binary树转换器。
     */
    private BinaryTreeConverter() {
    }

    /**
     * 将 B+ 树转换为二叉树（右斜链，前序序列）。
     *
     * @param tree B+ 树引擎
     * @param <K>  键类型
     * @param <V>  值类型
     * @return 二叉树根节点
     */
    public static <K extends Comparable<K>, V> TreeNode<K, V> bPlusToBinary(BPlusTree<K, V> tree) {
        List<Entry<K, V>> entries = collectEntries(tree);
        if (entries.isEmpty()) {
            return new TreeNode<>();
        }
        TreeNode<K, V> root = new TreeNode<>(entries.getFirst().key, entries.getFirst().value);
        TreeNode<K, V> cur = root;
        for (int i = 1; i < entries.size(); i++) {
            TreeNode<K, V> next = new TreeNode<>(entries.get(i).key, entries.get(i).value);
            cur.right = next;
            cur = next;
        }
        return root;
    }

    /**
     * 将二叉树（右斜链）恢复为 B+ 树引擎。
     *
     * @param root 二叉树根节点
     * @param <K>  键类型
     * @param <V>  值类型
     * @return 重建的 B+ 树引擎
     */
    public static <K extends Comparable<K>, V> BPlusTree<K, V> binaryToBPlusTree(TreeNode<K, V> root) {
        BPlusTree<K, V> tree = new BPlusTree<>(200);
        TreeNode<K, V> cur = root;
        while (cur != null) {
            if (cur.key != null) {
                tree.put(cur.key, cur.value);
            }
            cur = cur.right;
        }
        return tree;
    }

    /**
     * 将 B 树转换为二叉树（右斜链，中序序列）。
     *
     * @param tree B 树引擎
     * @param <K>  键类型
     * @param <V>  值类型
     * @return 二叉树根节点
     */
    public static <K extends Comparable<K>, V> TreeNode<K, V> bTreeToBinary(BTree<K, V> tree) {
        List<Entry<K, V>> entries = collectEntriesFromBTree(tree);
        if (entries.isEmpty()) {
            return new TreeNode<>();
        }
        TreeNode<K, V> root = new TreeNode<>(entries.getFirst().key, entries.getFirst().value);
        TreeNode<K, V> cur = root;
        for (int i = 1; i < entries.size(); i++) {
            TreeNode<K, V> next = new TreeNode<>(entries.get(i).key, entries.get(i).value);
            cur.right = next;
            cur = next;
        }
        return root;
    }

    /**
     * 将二叉树（右斜链）恢复为 B 树引擎。
     *
     * @param root 二叉树根节点
     * @param <K>  键类型
     * @param <V>  值类型
     * @return 重建的 B 树引擎
     */
    public static <K extends Comparable<K>, V> BTree<K, V> binaryToBTree(TreeNode<K, V> root) {
        BTree<K, V> tree = new BTree<>(200);
        TreeNode<K, V> cur = root;
        while (cur != null) {
            if (cur.key != null) {
                tree.put(cur.key, cur.value);
            }
            cur = cur.right;
        }
        return tree;
    }

    /**
     * 从 B+ 树收集全部有序条目（沿叶子链表遍历）。
     *
     * @param tree B+ 树引擎
     * @param <K>  键类型
     * @param <V>  值类型
     * @return 有序条目列表
     */
    private static <K extends Comparable<K>, V> List<Entry<K, V>> collectEntries(BPlusTree<K, V> tree) {
        List<Entry<K, V>> result = new ArrayList<>();
        BPlusTreeNode<K, V> cur = tree.getRoot();
        while (cur != null && !cur.leaf) {
            cur = cur.children.getFirst();
        }
        while (cur != null) {
            for (int i = 0; i < cur.keys.size(); i++) {
                result.add(new Entry<>(cur.keys.get(i), cur.values.get(i)));
            }
            cur = cur.next;
        }
        return result;
    }

    /**
     * 从 B 树收集全部有序条目（中序遍历）。
     *
     * @param tree B 树引擎
     * @param <K>  键类型
     * @param <V>  值类型
     * @return 有序条目列表
     */
    private static <K extends Comparable<K>, V> List<Entry<K, V>> collectEntriesFromBTree(BTree<K, V> tree) {
        List<Entry<K, V>> result = new ArrayList<>();
        inOrderCollect(tree.root, result);
        return result;
    }

    /**
     * 中序遍历 B 树节点，将条目追加到结果列表。
     *
     * @param node   当前节点
     * @param result 结果收集列表
     * @param <K>    键类型
     * @param <V>    值类型
     */
    private static <K extends Comparable<K>, V> void inOrderCollect(BTreeNode<K, V> node,
                                                                     List<Entry<K, V>> result) {
        if (node == null) {
            return;
        }
        for (int i = 0; i < node.keys.size(); i++) {
            if (!node.leaf) {
                inOrderCollect(node.children.get(i), result);
            }
            result.add(new Entry<>(node.keys.get(i), node.values.get(i)));
        }
        if (!node.leaf) {
            inOrderCollect(node.children.get(node.keys.size()), result);
        }
    }

    /**
     * 有序键值条目。
     *
     * <p>B+ 树 / B 树与二叉树双向转换过程中的中间载体：先按树的键顺序把每个
     * {@code (key, value)} 对收集成条目列表，再把列表依次串成一条右斜链二叉树。
     * 因此条目在列表中的先后，就是重建后二叉树的遍历顺序，也决定了
     * {@code binaryToBPlusTree} / {@code binaryToBTree} 写回键值的先后次序。</p>
     *
     * <p>本 record 是纯内部实现细节，不做防御性拷贝：两个组件一律由
     * {@code new Entry<>(cur.keys.get(i), cur.values.get(i))} 按节点内同一列表下标配对产生，
     * 生命周期仅限单次转换调用，不跨线程共享。键类型受两个公开转换方法的
     * {@code K extends Comparable<K>} 约束，故列表天然有序。</p>
     *
     * @param key 键，取自树节点内按升序排列的键列表，与 {@code value} 按同一列表下标配对；
     *            重建成树时对 null 键直接跳过（不调用 {@code put}），故 null 键不参与写回
     * @param value 值，与 {@code key} 同下标配对；B 树 / B+ 树对值无非空约束，允许为 null
     */
    private record Entry<K, V>(K key, V value) {
    }
}
