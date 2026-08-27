package com.chua.common.support.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 二叉树节点 — 用于 B+/B 树与二叉树之间的相互转换。
 *
 * <p>转换规则：
 * <ul>
 *   <li>B+ 树 → 二叉树：每个节点展开为根，第一个子节点为左子，下一个兄弟为右子（left-child right-sibling）</li>
 *   <li>二叉树 → B+ 树：前序遍历重建，左子链展开为子节点列表，右子链跳过</li>
 * </ul>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author CH
 * @since 4.0.0.42
 */
public class TreeNode<K, V> {

    /** 键，可为 null（空节点占位） */
    K key;
    /** 值 */
    V value;
    /** 左子节点（左孩子-右兄弟表示法的第一个子节点） */
    TreeNode<K, V> left;
    /** 右兄弟节点或右斜链的下一个节点 */
    TreeNode<K, V> right;
    /** 子节点列表，用于 B+ 树内部节点的多叉表示 */
    List<TreeNode<K, V>> children;

    public TreeNode() {
        this.children = new ArrayList<>();
    }

    public TreeNode(K key, V value) {
        this.key = key;
        this.value = value;
        this.children = new ArrayList<>();
    }

    public boolean isLeaf() {
        return left == null && right == null && children.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TreeNode<?, ?> that)) {
            return false;
        }
        return Objects.equals(key, that.key) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value);
    }

    @Override
    public String toString() {
        return "TreeNode{key=" + key + ", value=" + value + "}";
    }
}
