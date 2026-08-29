package com.chua.common.support.tree;

import java.util.ArrayList;
import java.util.List;

/**
 * B 树节点（数据存储在内部节点，允许节点存储键值对）。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author CH
 * @since 4.0.0.42
 */
class BTreeNode<K, V> {

    List<K> keys = new ArrayList<>();
    List<V> values = new ArrayList<>();
    List<BTreeNode<K, V>> children = new ArrayList<>();
    boolean leaf;

    BTreeNode(boolean leaf) {
        this(leaf, 16);
    }

    BTreeNode(boolean leaf, int capacity) {
        this.leaf = leaf;
        if (keys instanceof ArrayList) ((ArrayList<?>) keys).ensureCapacity(capacity);
        if (values instanceof ArrayList) ((ArrayList<?>) values).ensureCapacity(capacity);
        if (children instanceof ArrayList) ((ArrayList<?>) children).ensureCapacity(capacity + 1);
    }

    List<K> getKeys() { return keys; }
    List<V> getValues() { return values; }
    List<BTreeNode<K, V>> getChildren() { return children; }
    boolean isLeaf() { return leaf; }
    void setLeaf(boolean leaf) { this.leaf = leaf; }
    void setKeys(List<K> keys) { this.keys = keys; }
    void setValues(List<V> values) { this.values = values; }
    void setChildren(List<BTreeNode<K, V>> children) { this.children = children; }
}
