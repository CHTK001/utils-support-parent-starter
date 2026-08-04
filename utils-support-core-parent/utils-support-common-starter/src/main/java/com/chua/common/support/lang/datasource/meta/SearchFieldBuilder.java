package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import org.jspecify.annotations.NullUnmarked;

/**
 * 搜索引擎字段构建器。
 * <p>
 * 用于配置索引中单个字段的详细属性，如分词器、是否索引、是否存储等。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * engine.meta().search().create("product")
 *     .field("title", "text", cfg -> cfg
 *         .analyzer("ik_max_word")
 *         .searchAnalyzer("ik_smart")
 *         .index(true)
 *         .store(false))
 *     .field("price", "integer")
 *     .execute();
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public interface SearchFieldBuilder {

    /**
     * 设置分词器。
     *
     * @param analyzer 分词器名称
     * @return this
     */
    SearchFieldBuilder analyzer(String analyzer);

    /**
     * 设置搜索分词器。
     *
     * @param searchAnalyzer 搜索分词器名称
     * @return this
     */
    SearchFieldBuilder searchAnalyzer(String searchAnalyzer);

    /**
     * 设置是否建立索引。
     *
     * @param indexed 是否索引
     * @return this
     */
    SearchFieldBuilder index(boolean indexed);

    /**
     * 设置是否存储原文。
     *
     * @param stored 是否存储
     * @return this
     */
    SearchFieldBuilder store(boolean stored);

    /**
     * 快速设置为 keyword 类型（不分词，精确匹配）。
     *
     * @return this
     */
    SearchFieldBuilder keyword();

    /**
     * 快速设置为 text 类型（分词，全文检索）。
     *
     * @return this
     */
    SearchFieldBuilder text();

    /**
     * 快速设置为 integer 类型。
     *
     * @return this
     */
    SearchFieldBuilder integer();

    /**
     * 快速设置为 long 类型。
     *
     * @return this
     */
    SearchFieldBuilder longType();

    /**
     * 快速设置为 float 类型。
     *
     * @return this
     */
    SearchFieldBuilder floatType();

    /**
     * 快速设置为 double 类型。
     *
     * @return this
     */
    SearchFieldBuilder doubleType();

    /**
     * 快速设置为 date 类型。
     *
     * @return this
     */
    SearchFieldBuilder date();

    /**
     * 快速设置为 boolean 类型。
     *
     * @return this
     */
    SearchFieldBuilder bool();

    /**
     * 快速设置为 object 类型（嵌套对象）。
     *
     * @return this
     */
    SearchFieldBuilder object();

    /**
     * 快速设置为 nested 类型（嵌套对象，支持内嵌查询）。
     *
     * @return this
     */
    SearchFieldBuilder nested();

    /**
     * 设置字段权重。
     *
     * @param weight 权重值
     * @return this
     */
    SearchFieldBuilder weight(double weight);

    /**
     * 设置字段忽略上限（超过此长度的字段将被忽略）。
     *
     * @param ignoreAbove 字符数上限
     * @return this
     */
    SearchFieldBuilder ignoreAbove(int ignoreAbove);

    /**
     * 设置字段是否支持聚合。
     *
     * @param docValues 是否支持聚合
     * @return this
     */
    SearchFieldBuilder docValues(boolean docValues);

    /**
     * 设置字段为空值时使用的默认值。
     *
     * @param nullValue 默认值
     * @return this
     */
    SearchFieldBuilder nullValue(String nullValue);
}
