package com.chua.calcite.support.datasource;

import com.chua.datasource.support.datasource.DataTable;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calcite 数据表实现类。
 * 用于封装 Calcite 查询结果或构建内存中的虚拟表结构。
 *
 * @author CH
 * @since 4.0.0.42
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
public class CalciteDataTable implements DataTable {

    /**
     * 数据表的名称。
     */
    private final String name;

    /**
     * 列名列表。
     */
    private final List<String> columnNames;

    /**
     * 列类型列表。
     */
    private final List<Class<?>> columnTypes;

    /**
     * 行数据列表，每行是一个键值对映射。
     */
    private final List<Map<String, Object>> data;

    /**
     * 构造一个新的 Calcite 数据表实例。
     *
     * @param name          表名
     * @param columnNames   列名集合
     * @param columnTypes   列类型集合
     */
    public CalciteDataTable(String name, List<String> columnNames, List<Class<?>> columnTypes) {
        this.name = name;
        this.columnNames = List.copyOf(columnNames);
        this.columnTypes = List.copyOf(columnTypes);
        this.data = new ArrayList<>();
    }

    /**
     * 获取列类型列表。
     *
     * @return 列类型列表
     */
    public List<Class<?>> getColumnTypes() {
        return columnTypes;
    }

    /**
     * 向当前数据表中添加一行数据。
     *
     * @param values 列对应的值数组
     * @return 当前实例，支持链式调用
     */
    public CalciteDataTable addRow(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        int size = Math.min(columnNames.size(), values.length);
        for (int i = 0; i < size; i++) {
            row.put(columnNames.get(i), values[i]);
        }
        data.add(row);
        return this;
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
