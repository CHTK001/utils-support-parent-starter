package com.chua.common.support.vector;

import java.util.List;

/**
 * 向量存储接口，提供向量的添加、搜索和管理能力。
 * <p>
 * 通过 {@link VectorStorageBuilder} 链式构建实例：
 * </p>
 * <pre>{@code
 * var storage = VectorStorageBuilder.newBuilder()
 *         .dimension(128)
 *         .algorithm("COSINE")
 *         .build();
 * storage.add(new Vector("id1", new float[]{...}));
 * var results = storage.search(queryVector, 10);
 * }</pre>
 *
 * @author CH
 * @since 2024/12/12
 */
public interface VectorStorage extends AutoCloseable {

    /**
     * 获取向量维度。
     *
     * @return 向量维度
     */
    int dimension();

    /**
     * 添加向量到存储。
     *
     * @param id     向量标识
     * @param vector 向量数据
     * @return 是否成功
     */
    boolean add(String id, float[] vector);

    /**
     * 添加向量对象到存储。
     *
     * @param vector 向量对象
     * @return 是否成功
     */
    default boolean add(Vector vector) {
        return add(vector.id(), vector.data());
    }

    /**
     * 搜索与查询向量最相似的 Top-K 个向量。
     *
     * @param query 查询向量
     * @param topK  返回结果数量
     * @return 按相似度排序的向量列表
     */
    List<Vector> search(float[] query, int topK);

    /**
     * 搜索与查询向量最相似的 Top-K 个向量。
     *
     * @param query 查询向量对象
     * @param topK  返回结果数量
     * @return 按相似度排序的向量列表
     */
    default List<Vector> search(Vector query, int topK) {
        return search(query.data(), topK);
    }

    /**
     * 获取存储中的向量总数。
     *
     * @return 向量数量
     */
    int size();

    /**
     * 清空所有向量。
     */
    void clear();

    /**
     * 关闭存储，释放资源。
     */
    @Override
    void close();
}
