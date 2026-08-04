package com.chua.common.support.lang.datasource.meta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullUnmarked;

/**
 * 搜索引擎索引定义，描述 Elasticsearch / Solr / RedisSearch 等搜索引擎的索引结构。
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchIndexDef {

    /**
     * 索引名
     */
    private String name;

    /**
     * 主分片数
     */
    private Integer shards;

    /**
     * 副本数
     */
    private Integer replicas;

    /**
     * 字段定义列表
     */
    private List<SearchFieldDef> fields;

    /**
     * 索引设置（如 refresh_interval、max_result_window 等）
     */
    private Map<String, Object> settings;

    /**
     * 映射定义（Mappings），搜索引擎特定结构
     */
    private Map<String, Object> mappings;
}
