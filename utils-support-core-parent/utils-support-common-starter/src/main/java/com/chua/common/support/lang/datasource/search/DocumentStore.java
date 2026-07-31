package com.chua.common.support.lang.datasource.search;

import java.util.List;

/**
 * 文档存储接口，提供类 MongoDB 的文档级 CRUD 能力。
 * <p>
 * 支持 collection 维度的文档插入、按 ID 查询、更新、删除与全量扫描，
 * 不依赖预定义实体表结构。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DocumentStore {

    /**
     * 向指定 collection 插入文档。
     *
     * @param collection collection 名称
     * @param document 文档对象（POJO 或 {@code Map<String, Object>}）
     * @param <T> 文档类型
     * @return 插入后的文档对象
     */
    <T> T insert(String collection, T document);

    /**
     * 根据 ID 查找文档。
     *
     * @param collection collection 名称
     * @param id 文档 ID
     * @param documentClass 文档类型
     * @param <T> 文档类型
     * @return 文档对象，不存在时返回 {@code null}
     */
    <T> T findById(String collection, Object id, Class<T> documentClass);

    /**
     * 更新指定 ID 的文档（全量替换）。
     *
     * @param collection collection 名称
     * @param id 文档 ID
     * @param document 新文档对象
     * @param <T> 文档类型
     * @return 更新后的文档对象，不存在时返回 {@code null}
     */
    <T> T update(String collection, Object id, T document);

    /**
     * 删除指定 ID 的文档。
     *
     * @param collection collection 名称
     * @param id 文档 ID
     * @return 是否删除成功
     */
    boolean delete(String collection, Object id);

    /**
     * 查询 collection 下的所有文档。
     *
     * @param collection collection 名称
     * @param documentClass 文档类型
     * @param <T> 文档类型
     * @return 文档列表
     */
    <T> List<T> findAll(String collection, Class<T> documentClass);
}
