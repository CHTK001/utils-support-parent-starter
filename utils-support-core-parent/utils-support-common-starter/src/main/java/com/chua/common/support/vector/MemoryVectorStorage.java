package com.chua.common.support.vector;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量存储实现，适用于测试和小规模场景。
 * <p>
 * 所有向量数据保存在内存中，搜索时遍历全部向量计算距离。
 * 存储完整的 {@link Vector} 对象，包含原文内容和元数据。
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
public class MemoryVectorStorage extends AbstractVectorStorage {

    /** store */
    private final Map<String, Vector> store = new ConcurrentHashMap<>();

    /**
     * 构造内存向量存储。
     *
     * @param dimension 向量维度
     * @param algorithm 比较算法
     */
    public MemoryVectorStorage(int dimension, VectorCompareAlgorithm algorithm) {
        super(dimension, algorithm);
    }

    @Override
    public boolean add(Vector vector) {
        checkNotClosed();
        if (vector.data() != null && vector.data().length != dimension()) {
            throw new IllegalArgumentException(
                    "维度不匹配: 期望 " + dimension() + ", 实际 " + vector.data().length);
        }
        if (store.containsKey(vector.id())) {
            return false;
        }
        store.put(vector.id(), vector);
        return true;
    }

    @Override
    protected boolean doAdd(String id, float[] vector) {
        if (store.containsKey(id)) {
            return false;
        }
        store.put(id, new Vector(id, vector));
        return true;
    }

    @Override
    public boolean remove(String id) {
        checkNotClosed();
        return store.remove(id) != null;
    }

    @Override
    public boolean update(String id, float[] vector) {
        checkNotClosed();
        // 与 JVector/Milvus 及基类 add/search 惯例一致：先校验维度（编程错误 fail-fast），再查 id 存在性
        if (vector.length != dimension()) {
            throw new IllegalArgumentException(
                    "维度不匹配: 期望 " + dimension() + ", 实际 " + vector.length);
        }
        if (!store.containsKey(id)) {
            return false;
        }
        store.put(id, new Vector(id, vector));
        return true;
    }

    @Override
    protected List<Vector> doSearch(float[] query, int topK) {
        var algo = getAlgorithm() != null ? getAlgorithm() : VectorCompareAlgorithm.euclidean();
        return store.values().stream()
                .map(v -> new Vector(v.id(), v.data(), mergeMetadata(v, algo.compare(query, v.data())), v.content()))
                .sorted(Comparator.comparingDouble(
                        v -> ((Number) v.metadata().get("score")).doubleValue()))
                .limit(topK)
                .toList();
    }

    private static Map<String, Object> mergeMetadata(Vector v, double score) {
        var meta = new java.util.LinkedHashMap<String, Object>();
        meta.put("score", score);
        if (v.metadata() != null) {
            meta.putAll(v.metadata());
        }
        return Map.copyOf(meta);
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public void clear() {
        store.clear();
    }
}
