package com.chua.common.support.lang.datasource.meta;

/**
 * ALTER TABLE 中的索引构建器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface AlterIndexBuilder {

    /**
     * 设置索引列。
     *
     * @param columnName 列名
     * @return this
     */
    AlterIndexBuilder column(String columnName);

    /**
     * 设置为唯一索引。
     *
     * @return this
     */
    AlterIndexBuilder unique();

    /**
     * 设置索引类型（BTREE / HASH / FULLTEXT）。
     *
     * @param type 索引类型
     * @return this
     */
    AlterIndexBuilder type(String type);

    /**
     * 执行索引创建。
     *
     * @return this
     */
    TableAlterBuilder execute();
}
