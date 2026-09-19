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

        CreateIndexStep onTable(String table);

        CreateIndexStep field(String field);

        CreateIndexStep onColumn(String column);

        CreateIndexStep type(String type);

        CreateIndexStep using(String algorithm);

        void execute();
    }

    /**
     * 删除索引步骤接口。
     *
     * @author CH
     * @since 4.0.0
     */
    interface DropIndexStep {

        DropIndexStep onTable(String table);

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
