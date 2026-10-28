package com.chua.datasource.support.index;

import java.util.List;

/**
 * 索引管理器 SPI 接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface IndexManager {

    /**
     * 创建索引步骤接口。
     *
     * @author CH
     * @since 4.0.0
     */
    interface CreateIndexStep {

    /**
     * 指定操作的表。
     *
     * @param table 表名
     * @return 当前步骤，便于链式调用
     */
        CreateIndexStep onTable(String table);

    /**
     * 指定索引字段。
     *
     * @param field 字段名
     * @return 当前步骤，便于链式调用
     */
        CreateIndexStep field(String field);

    /**
     * 指定操作的列。
     *
     * @param column 列名
     * @return 当前步骤，便于链式调用
     */
        CreateIndexStep onColumn(String column);

    /**
     * 指定索引类型。
     *
     * @param type 索引类型
     * @return 当前步骤，便于链式调用
     */
        CreateIndexStep type(String type);

    /**
     * 指定索引算法。
     *
     * @param algorithm 索引算法
     * @return 当前步骤，便于链式调用
     */
        CreateIndexStep using(String algorithm);

    /**
     * 执行已配置的操作。
     */
        void execute();
    }

    /**
     * 删除索引步骤接口。
     *
     * @author CH
     * @since 4.0.0
     */
    interface DropIndexStep {

    /**
     * 指定操作的表。
     *
     * @param table 表名
     * @return 当前步骤，便于链式调用
     */
        DropIndexStep onTable(String table);

    /**
     * 执行已配置的操作。
     */
        void execute();
    }

    /**
     * 返回 SPI 扩展键。
     *
     * @return 数据库类型标识
     */
    String type();

    /**
     * 查询表上的所有索引。
     *
     * @param table 表名
     * @return 索引名列表
     */
    List<String> listIndexes(String table);

    /**
     * 创建索引。
     *
     * @param indexName 索引名
     * @return 创建索引步骤
     */
    CreateIndexStep createIndex(String indexName);

    /**
     * 删除索引。
     *
     * @param indexName 索引名
     * @return 删除索引步骤
     */
    DropIndexStep dropIndex(String indexName);
}
