package com.chua.datasource.support.datasource;

import javax.sql.DataSource;

/**
 * 数据源创建器接口。
 * <p>
   * 统一管理多种类型的数据源（JDBC 数据源、数据scheme 等），
   * 通过 SPI 机制可以注册不同的实现（如 calcite数据源creator）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DataSourceCreator {

    /**
     * 添加一个 JDBC 数据源。
     *
     * @param name       数据源名称
     * @param dataSource JDBC 数据源
     * @return this
     */
    DataSourceCreator addDataSource(String name, DataSource dataSource);

    /**
     * 添加一个数据方案。
     *
     * @param scheme 数据方案
     * @return this
     */
    DataSourceCreator addScheme(DataScheme scheme);

    /**
     * 在指定方案下添加一张表。
     *
     * @param schemaName 方案名
     * @param table      数据表
     * @return this
     */
    DataSourceCreator addTable(String schemaName, DataTable table);
}