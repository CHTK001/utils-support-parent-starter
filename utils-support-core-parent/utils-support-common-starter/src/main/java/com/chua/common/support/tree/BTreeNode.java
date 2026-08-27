package com.chua.common.support.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

    /**
     * 设置初始容量以避免频繁扩容。
     *
     * @param initialCapacity 初始容量
     */
    void setInitialCapacity(int initialCapacity) {
        if (keys instanceof ArrayList) {
            ((ArrayList<?>) keys).ensureCapacity(initialCapacity);
        }
        if (values instanceof ArrayList) {
            ((ArrayList<?>) values).ensureCapacity(initialCapacity);
        }
        if (children instanceof ArrayList) {
            ((ArrayList<?>) children).ensureCapacity(initialCapacity + 1);
        }
    }

    BTreeNode(boolean leaf) {
        this.leaf = leaf;
    }

    /**
     * 判断节点是否已满。
     *
     * @param order B 树阶数
     * @return 是否已满
     */
    boolean isFull(int order) {
        return keys.size() >= order - 1;
    }

    /**
     * 判断节点是否为空。
     *
     * @return 是否空
     */
    boolean isEmpty() {
        return keys.isEmpty();
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
