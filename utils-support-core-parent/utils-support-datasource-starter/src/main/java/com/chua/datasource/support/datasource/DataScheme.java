package com.chua.datasource.support.datasource;

import java.util.List;

/**
 * 数据方案接口，对应数据库中的一个库（模式）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DataScheme extends AutoCloseable {

    /**
     * 获取方案名称。
     *
     * @return 名称
     */
    String getName();

    /**
     * 获取所有表名。
     *
     * @return 表名列表
     */
    List<String> getTableNames();

    /**
     * 根据名称获取表。
     *
     * @param name 表名
     * @return 表实例
     */
    DataTable getTable(String name);
}
