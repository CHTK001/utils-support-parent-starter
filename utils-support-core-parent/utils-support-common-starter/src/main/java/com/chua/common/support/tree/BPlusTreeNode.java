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
    /** 当前节点所有键，有序排列 */
    List<K> keys = new ArrayList<>();
    /** 与 keys 一一对应的值 */
    List<V> values = new ArrayList<>();
    /** 内部节点的子节点列表（叶子节点为空） */
    List<BPlusTreeNode<K, V>> children = new ArrayList<>();
    /** 叶子节点之间的链表后继指针；内部节点为 null */
    BPlusTreeNode<K, V> next;

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

    List<K> getKeys() { return keys; }
    List<V> getValues() { return values; }
    List<BPlusTreeNode<K, V>> getChildren() { return children; }
    BPlusTreeNode<K, V> getNext() { return next; }
    void setNext(BPlusTreeNode<K, V> next) { this.next = next; }
    void setKeys(List<K> keys) { this.keys = keys; }
    void setValues(List<V> values) { this.values = values; }
    void setChildren(List<BPlusTreeNode<K, V>> children) { this.children = children; }
}
