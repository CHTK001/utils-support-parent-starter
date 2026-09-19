package com.chua.datasource.support.datasource;

import java.util.List;
import java.util.Map;

/**
 * 数据表接口，对应数据库中的一张表。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DataTable {

    /**
     * 获取表名。
     *
     * @return 表名
     */
    String getName();

    /**
     * 获取列名列表。
     *
     * @return 列名
     */
    List<String> getColumnNames();

    /**
     * 获取全部数据。
     *
     * @return 行数据
     */
    List<Map<String, Object>> getData();

    /**
     * 获取行数。
     *
     * @return 行数
     */
    long getRowCount();
}
