package com.chua.common.support.vector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * 向量存储抽象基类，封装了维度校验、资源关闭等通用逻辑。
 * <p>
 * 子类只需实现 {@link #doAdd(String, float[])} 和 {@link #doSearch(float[], int)} 方法。
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
public abstract class AbstractVectorStorage implements VectorStorage {

    /**
     * 向量维度。
     */
    private final int dimension;

    /**
     * 比较算法。
     */
    private final VectorCompareAlgorithm algorithm;

    /**
     * 向量存储是否已关闭。
     */
    private volatile boolean closed;

    /**
     * 构造抽象向量存储。
     *
     * @param dimension 向量维度
     * @param algorithm 比较算法（可为 null，默认欧几里得）
     */
    protected AbstractVectorStorage(int dimension, VectorCompareAlgorithm algorithm) {
        this.dimension = dimension;
        this.algorithm = algorithm;
    }

    /**
     * 获取比较算法。
     *
     * @return 比较算法
     */
    protected VectorCompareAlgorithm getAlgorithm() {
        return algorithm;
    }

    @Override
    /** Dimension */
    public int dimension() {
        checkNotClosed();
        return dimension;
    }

    @Override
    /** 添加 */
    public boolean add(String id, float[] vector) {
        checkNotClosed();
        if (vector.length != dimension) {
            throw new IllegalArgumentException(
                    "维度不匹配: 期望 " + dimension + ", 实际 " + vector.length);
        }
        return doAdd(id, vector);
    }

    @Override
    /** 搜索 */
    public List<Vector> search(float[] query, int topK) {
        checkNotClosed();
        if (query.length != dimension) {
            throw new IllegalArgumentException(
                    "查询向量维度不匹配: 期望 " + dimension + ", 实际 " + query.length);
        }
        return doSearch(query, topK);
    }

    /**
     * 子类实现：添加向量。
     *
     * @param id     向量标识
     * @param vector 向量数据
     * @return 是否成功
     */
    protected abstract boolean doAdd(String id, float[] vector);

    /**
     * 子类实现：搜索最近邻。
     *
     * @param query 查询向量
     * @param topK  返回数量
     * @return 搜索结果
     */
    protected abstract List<Vector> doSearch(float[] query, int topK);

    /**
     * 二次过滤：使用 {@link #getAlgorithm()} 对结果重新排序并截断。
     *
     * <p>适用于远程向量数据库（如 Milvus）——查询端先取回更多候选结果，
     * 再通过自定义算法的 {@link VectorCompareAlgorithm#compare(float[], float[])} 精确计算距离并重新排序，
     * 最终返回 Top-K 个结果。</p>
     *
     * @param results 候选结果列表（需包含向量数据）
     * @param query   查询向量
     * @param topK    最终需要的数量
     * @return 按自定义算法排序的前 topK 个结果
     */
    protected List<Vector> rerank(List<Vector> results, float[] query, int topK) {
        VectorCompareAlgorithm algo = getAlgorithm();
        if (algo == null || results.isEmpty()) {
            return results.size() > topK ? results.subList(0, topK) : results;
        }
        results.sort((a, b) -> Float.compare(
                algo.compare(b.data(), query),
                algo.compare(a.data(), query)));
        return results.size() > topK ? results.subList(0, topK) : results;
    }

    @Override
    /** 关闭 */
    public void close() {
        this.closed = true;
    }

    /**
     * 检查存储是否已关闭。
     */
    protected void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("向量存储已关闭");
        }
    }
}
