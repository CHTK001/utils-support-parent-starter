package com.chua.datasource.support.datasource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 可变数据表实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MutableDataTable implements DataTable {

    /**
     * 表名。
     */
    private final String name;

    /**
     * 列名列表。
     */
    private final List<String> columnNames;

    /**
     * 行数据列表。
     */
    private final List<Map<String, Object>> data;

    /**
     * 创建 mutable数据table 实例
     * @param name 名称
     */
    public MutableDataTable(String name) {
        this.name = name;
        this.columnNames = new ArrayList<>();
        this.data = new ArrayList<>();
    }

    /**
     * 添加列。
     *
     * @param column 列名
     */
    public void addColumn(String column) {
        columnNames.add(column);
    }

    /**
     * 添加行。
     *
     * @param row 行数据
     */
    public void addRow(Map<String, Object> row) {
        data.add(row);
    }

    @Override
    /**
     * 获取名称
    */
    public String getName() {
        return name;
    }

    @Override
    /**
     * 获取column名称
    */
    public List<String> getColumnNames() {
        return columnNames;
    }

    @Override
    /**
     * 获取数据
    */
    public List<Map<String, Object>> getData() {
        return data;
    }

    @Override
    /**
     * 获取Row计算数量
    */
    public long getRowCount() {
        return data.size();
    }
}
