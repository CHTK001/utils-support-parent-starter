package com.chua.common.support.vector;

import com.chua.common.support.tree.BPlusTree;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存向量存储实现，适用于测试和小规模场景。
 * <p>
 * 使用 {@link BPlusTree} 作为主索引（O(log N) 查找），替代原 {@link ConcurrentHashMap}
 * 的线性遍历，同时保持线程安全的读写锁语义。搜索时遍历全部向量计算距离。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MemoryVectorStorage extends AbstractVectorStorage {

    /** B+ Tree 主索引：id → Vector，提供 O(log N) 点查和有序遍历。 */
    private final BPlusTree<String, Vector> store = new BPlusTree<>(128);

    /** 读写锁：写操作（add/remove/update/clear）独占，读操作（search/size）共享。 */
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

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
        rwLock.writeLock().lock();
        try {
            if (store.containsKey(vector.id())) return false;
            store.put(vector.id(), vector);
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    protected boolean doAdd(String id, float[] vector) {
        if (vector.length != dimension()) {
            throw new IllegalArgumentException(
                    "维度不匹配: 期望 " + dimension() + ", 实际 " + vector.length);
        }
        rwLock.writeLock().lock();
        try {
            if (store.containsKey(id)) return false;
            store.put(id, new Vector(id, vector));
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public boolean remove(String id) {
        checkNotClosed();
        rwLock.writeLock().lock();
        try {
            return store.remove(id).isPresent();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public int removeByIdPrefix(String idPrefix) {
        checkNotClosed();
        rwLock.writeLock().lock();
        try {
            List<String> toRemove = new java.util.ArrayList<>();
            for (Map.Entry<String, Vector> entry : allEntries()) {
                if (entry.getKey().startsWith(idPrefix)) {
                    toRemove.add(entry.getKey());
                }
            }
            toRemove.forEach(store::remove);
            return toRemove.size();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public boolean update(String id, float[] vector) {
        checkNotClosed();
        if (vector.length != dimension()) {
            throw new IllegalArgumentException(
                    "维度不匹配: 期望 " + dimension() + ", 实际 " + vector.length);
        }
        rwLock.writeLock().lock();
        try {
            if (!store.containsKey(id)) return false;
            store.put(id, new Vector(id, vector));
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    protected List<Vector> doSearch(float[] query, int topK) {
        var algo = getAlgorithm() != null ? getAlgorithm() : VectorCompareAlgorithm.euclidean();
        rwLock.readLock().lock();
        try {
            List<Vector> results = new java.util.ArrayList<>();
            for (Map.Entry<String, Vector> entry : allEntries()) {
                Vector v = entry.getValue();
                double score = algo.compare(query, v.data());
                results.add(new Vector(v.id(), v.data(),
                        mergeMetadata(v, score), v.content()));
            }
            results.sort(Comparator.comparingDouble(
                    v -> ((Number) v.metadata().get("score")).doubleValue()));
            return results.subList(0, Math.min(topK, results.size()));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        rwLock.readLock().lock();
        try {
            return store.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void clear() {
        rwLock.writeLock().lock();
        try {
            store.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** 合并元数据，score 插入到最前面。 */
    private static Map<String, Object> mergeMetadata(Vector v, double score) {
        var meta = new java.util.LinkedHashMap<String, Object>();
        meta.put("score", score);
        if (v.metadata() != null) meta.putAll(v.metadata());
        return Map.copyOf(meta);
    }

    /** 遍历 B+ Tree 全部条目（通过 range 查询）。 */
    private List<Map.Entry<String, Vector>> allEntries() {
        return store.range(null, null);
    }
}
