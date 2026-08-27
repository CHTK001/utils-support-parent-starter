package com.chua.common.support.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * B+ 树节点。
 *
 * <p>内部节点存储分隔键与子节点指针；叶子节点存储键值对，并通过 {@code next}
 * 指针串联形成有序链表，支持高效的范围扫描。</p>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author CH
 * @since 4.0.0.42
 */
class BPlusTreeNode<K, V> {

    final boolean leaf;
    final List<K> keys = new ArrayList<>();
    final List<V> values = new ArrayList<>();
    final List<BPlusTreeNode<K, V>> children = new ArrayList<>();
    BPlusTreeNode<K, V> next;

    BPlusTreeNode(boolean leaf) {
        this.leaf = leaf;
    }

    /**
     * 判断节点是否已满（不能继续插入）。
     *
     * @param order B+ 树阶数
     * @return 是否已满
     */
    boolean isFull(int order) {
        return leaf ? keys.size() >= order - 1 : children.size() >= order;
    }

    /**
     * 判断节点是否为空。
     *
     * @return 是否空
     */
    boolean isEmpty() {
        return keys.isEmpty() && children.isEmpty();
    }
}
