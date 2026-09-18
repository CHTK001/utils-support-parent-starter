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

    final boolean leaf; // leaf
    /** 当前节点所有键，有序排列 */
    List<K> keys = new ArrayList<>();
    /** 与 键 一一对应的值 */
    List<V> values = new ArrayList<>();
    /** 内部节点的子节点列表（叶子节点为空） */
    List<BPlusTreeNode<K, V>> children = new ArrayList<>();
    /** 叶子节点之间的链表后继指针；内部节点为 空 */
    BPlusTreeNode<K, V> next;

    /**
     * 构造方法，创建 BPlusTree节点 实例。
     *
     * @param leaf leaf（布尔开关）
     */
    BPlusTreeNode(boolean leaf) {
        this(leaf, 16);
    }

    /**
     * 构造方法，创建 BPlusTree节点 实例。
     *
     * @param leaf leaf（布尔开关）
     * @param capacity 方法入参 capacity
     */
    BPlusTreeNode(boolean leaf, int capacity) {
        this.leaf = leaf;
        if (keys instanceof ArrayList) {
            ((ArrayList<?>) keys).ensureCapacity(capacity);
        }
        if (values instanceof ArrayList) {
            ((ArrayList<?>) values).ensureCapacity(capacity);
        }
        if (children instanceof ArrayList) {
            ((ArrayList<?>) children).ensureCapacity(capacity + 1);
        }
    }

    /**
    * 返回 键 的当前容量（用于拷贝时预估新节点大小）。
    * @return 结果数值
    */
    int keysCapacity() {
        return keys instanceof ArrayList ? ((ArrayList<?>) keys).size() : keys.size();
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
