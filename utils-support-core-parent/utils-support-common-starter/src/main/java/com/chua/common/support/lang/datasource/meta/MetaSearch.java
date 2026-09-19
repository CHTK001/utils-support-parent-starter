package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 搜索引擎索引元数据操作接口。
 * <p>
 * 提供 Elasticsearch / Solr / RedisSearch 等搜索引擎索引的
 * 查询、创建、删除、刷新等操作。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * // ES 示例
 * engine.meta().search().create("product")
 *     .shards(3).replicas(1)
 *     .field("title", "text", cfg -> cfg.analyzer("ik_max_word"))
 *     .field("price", "integer")
 *     .field("status", "keyword")
 *     .settings(Map.of("refresh_interval", "5s"))
 *     .execute();
 *
 * // RedisSearch 示例
 * engine.meta().search().create("product_idx")
 *     .field("title", "TEXT").weight(1.0)
 *     .field("price", "NUMERIC")
 *     .execute();
 *
 * // Solr 示例
 * engine.meta().search().create("product")
 *     .field("title", "text_general")
 *     .field("price", "pint")
 *     .execute();
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface MetaSearch {

    /**
     * 列出当前数据源下的所有搜索引擎索引。
     *
     * @return 索引定义列表
     */
    List<SearchIndexDef> list();

    /**
     * 获取指定索引的定义。
     *
     * @param indexName 索引名
     * @return 索引定义
     */
    SearchIndexDef get(String indexName);

    /**
     * 创建索引（链式构建器）。
     *
     * @param indexName 索引名
     * @return 创建索引构建器
     */
    SearchIndexCreateBuilder create(String indexName);

    /**
     * 删除索引。
     *
     * @param indexName 索引名
     * @return true 删除成功
     */
    boolean drop(String indexName);

    /**
     * 刷新索引（使最近操作对搜索可见）。
     *
     * @param indexName 索引名
     * @return true 操作成功
     */
    boolean refresh(String indexName);

    /**
     * 优化索引（合并段等）。
     *
     * @param indexName 索引名
     * @return true 操作成功
     */
    boolean optimize(String indexName);
}
