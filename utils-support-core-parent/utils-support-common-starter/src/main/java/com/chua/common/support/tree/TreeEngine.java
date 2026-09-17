package com.chua.common.support.tree;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;

/**
 * 树引擎接口 — 统一 B+/B 树的检索操作契约。
 *
 * <p>支持 {@code K} 键的快速精确查找、范围查询与增删改；
 * 所有实现类须保证线程安全或外部加锁。</p>
 *
 * @param <K> 键类型，须实现 {@link Comparable}
 * @param <V> 值类型
 * @author CH
 * @since 4.0.0.42
*/
public interface TreeEngine<K extends Comparable<K>, V> {

    /**
    * 引擎类型标识。
    */
    String type();

    /**
    * 精确查找：返回键对应的值（不存在返回 {@link Optional#empty()}）。
    *
    * @param key 查询键
    * @return 结果包装
    */
    Optional<V> get(K key);

    /**
    * 范围查询：返回 [从, 转为) 区间内所有键值对，按升序排列。
    *
    * @param from 下界（含）
    * @param to   上界（不含）
    * @return 区间内所有条目列表
    */
    List<Map.Entry<K, V>> range(K from, K to);

    /**
    * 插入或更新键值对。
    *
    * @param key   键
    * @param value 值
    * @return 被替换的旧值（如有）
    */
    Optional<V> put(K key, V value);

    /**
    * 删除指定键。
    *
    * @param key 待删除键
    * @return 被删除的值（如有）
    */
    Optional<V> remove(K key);

    /**
    * 是否包含指定键。
    *
    * @param key 查询键
    * @return 是否包含
    */
    boolean containsKey(K key);

    /**
    * 返回当前存储条目数量。
    */
    int size();

    /**
    * 是否空。
    */
    boolean isEmpty();

    /**
    * 清空索引。
    */
    void clear();

    /**
    * 构建一个 B+ 树引擎实例。
    *
    * @param order    B+ 树阶数，建议 100~512；阶数越大单节点容量越高，高度越低
    * @param <K>      键类型
    * @param <V>      值类型
    * @return B+ 树引擎
    */
    static <K extends Comparable<K>, V> TreeEngine<K, V> ofBPlusTree(int order) {
        return new BPlusTree<>(order);
    }

    /**
    * 构建一个 B+ 树引擎实例（默认阶数 200）。
    *
    * @param <K> 键类型
    * @param <V> 值类型
    * @return B+ 树引擎
    */
    static <K extends Comparable<K>, V> TreeEngine<K, V> ofBPlusTree() {
        return new BPlusTree<>(200);
    }

    /**
    * 构建一个 B 树引擎实例。
    *
    * @param order B 树阶数
    * @param <K>   键类型
    * @param <V>   值类型
    * @return B 树引擎
    */
    static <K extends Comparable<K>, V> TreeEngine<K, V> ofBTree(int order) {
        return new BTree<>(order);
    }

    /**
    * 构建一个 B 树引擎实例（默认阶数 200）。
    *
    * @param <K> 键类型
    * @param <V> 值类型
    * @return B 树引擎
    */
    static <K extends Comparable<K>, V> TreeEngine<K, V> ofBTree() {
        return new BTree<>(200);
    }

    /**
    * 将当前树引擎转换为二叉树表示（前序展开）。
    *
    * @return 二叉树根节点
    */
    TreeNode<K, V> toBinaryTree();

    /**
    * 从二叉树（右斜链）恢复为对应类型的树引擎。
    * 默认恢复为 B+ 树。
    *
    * @param root 二叉树根节点
    * @param <K>  键类型
    * @param <V>  值类型
    * @return 新建的 B+ 树引擎实例
    */
    static <K extends Comparable<K>, V> TreeEngine<K, V> fromBinaryTree(TreeNode<K, V> root) {
        return BinaryTreeConverter.binaryToBPlusTree(root);
    }
}
