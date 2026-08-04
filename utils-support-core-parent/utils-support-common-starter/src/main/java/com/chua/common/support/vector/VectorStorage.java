package com.chua.common.support.vector;

import java.util.List;
import org.jspecify.annotations.NullUnmarked;

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
@NullUnmarked
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
     * 删除指定 id 的向量。
     *
     * @param id 向量标识
     * @return 是否删除成功（id 不存在时返回 false）
     */
    default boolean remove(String id) {
        throw new UnsupportedOperationException("当前实现不支持 remove");
    }

    /**
     * 更新指定 id 的向量数据。
     *
     * @param id     向量标识
     * @param vector 新的向量数据
     * @return 是否更新成功（id 不存在时返回 false）
     */
    default boolean update(String id, float[] vector) {
        throw new UnsupportedOperationException("当前实现不支持 update");
    }

    /**
     * 清空所有向量。
     */
    void clear();

    /**
     * 重建索引（将内存中的图/量化状态持久化到磁盘）。
     * <p>
     * 默认实现为空；具体存储实现可选择支持。
     * </p>
     */
    default void rebuild() {
        // 默认无操作
    }

    /**
     * 关闭存储，释放资源。
     */
    @Override
    void close();
}
