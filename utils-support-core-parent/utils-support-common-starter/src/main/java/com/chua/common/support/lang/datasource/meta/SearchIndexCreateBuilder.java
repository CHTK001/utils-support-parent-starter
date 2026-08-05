package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 创建搜索引擎索引链式构建器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SearchIndexCreateBuilder {

    /**
     * 设置主分片数。
     *
     * @param shards 分片数
     * @return this
     */
    SearchIndexCreateBuilder shards(int shards);

    /**
     * 设置副本数。
     *
     * @param replicas 副本数
     * @return this
     */
    SearchIndexCreateBuilder replicas(int replicas);

    /**
     * 添加字段（简单方式）。
     * <p>字段类型由搜索引擎决定，无需额外配置时使用此方法。</p>
     *
     * @param name 字段名
     * @param type 字段类型
     * @return this
     */
    SearchIndexCreateBuilder field(String name, String type);

    /**
     * 添加字段（带详细配置）。
     *
     * @param name   字段名
     * @param type   字段类型
     * @param config 字段配置消费者（可设置 analyzer、indexed、stored 等）
     * @return this
     */
    SearchIndexCreateBuilder field(String name, String type, Consumer<SearchFieldBuilder> config);

    /**
     * 批量添加字段。
     *
     * @param fields 字段定义列表
     * @return this
     */
    SearchIndexCreateBuilder fields(List<SearchFieldDef> fields);

    /**
     * 设置索引设置项。
     *
     * @param settings 设置映射（如 refresh_interval、max_result_window 等）
     * @return this
     */
    SearchIndexCreateBuilder settings(Map<String, Object> settings);

    /**
     * 设置原始映射定义（直接透传给搜索引擎）。
     *
     * @param mappings 映射 JSON 结构
     * @return this
     */
    SearchIndexCreateBuilder mappings(Map<String, Object> mappings);

    /**
     * 执行建索引语句。
     *
     * @return 创建的索引定义
     */
    SearchIndexDef execute();
}
