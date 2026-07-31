package com.chua.common.support.lang.datasource.meta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 搜索引擎字段定义，描述索引中的一个字段。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchFieldDef {

    /**
     * 字段名
     */
    private String name;

    /**
     * 字段类型（ES: text/keyword/integer/long/float/double/date/boolean/object/nested；
     * Solr: text_general/string/pint/pfloat/date 等；
     * RedisSearch: TEXT/ NUMERIC/ GEO 等）
     */
    private String type;

    /**
     * 分词器（如 ik_max_word、standard、english 等）
     */
    private String analyzer;

    /**
     * 搜索分词器
     */
    private String searchAnalyzer;

    /**
     * 是否建立索引（默认 true）
     */
    private boolean indexed = true;

    /**
     * 是否存储原文（默认 false）
     */
    private boolean stored = false;

    /**
     * 字段权重（用于排序/打分，默认 1.0）
     */
    private double weight = 1.0;
}
