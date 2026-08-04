package com.chua.common.support.lang.datasource.search;

import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * 全文检索接口，提供统一的全文索引创建、查询与删除能力。
 * <p>
 * 各搜索引擎（如 Lucene、Nitrite）可实现该接口，通过 {@link com.chua.common.support.lang.datasource.engine.Engine}
 * 的 {@code meta()} 或直接获取实现实例使用。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public interface FulltextSearch {

    /**
     * 为指定实体类的字段创建全文索引。
     *
     * @param entityClass 实体类类型
     * @param fieldNames 需要创建全文索引的字段名列表
     * @param <T> 实体类型
     */
    <T> void createFulltextIndex(Class<T> entityClass, String... fieldNames);

    /**
     * 执行全文检索。
     *
     * @param query      检索关键词
     * @param entityClass 实体类类型
     * @param <T>        实体类型
     * @return 匹配的实体列表
     */
    <T> List<T> search(String query, Class<T> entityClass);

    /**
     * 执行全文检索（带结果数量限制）。
     *
     * @param query      检索关键词
     * @param entityClass 实体类类型
     * @param limit      最大返回条数
     * @param <T>        实体类型
     * @return 匹配的实体列表
     */
    <T> List<T> search(String query, Class<T> entityClass, int limit);

    /**
     * 删除指定实体类的全文索引。
     *
     * @param entityClass 实体类类型
     * @param fieldNames 需要删除索引的字段名列表
     * @param <T> 实体类型
     */
    <T> void dropFulltextIndex(Class<T> entityClass, String... fieldNames);
}
