package com.chua.datasource.support.datasource;
import com.chua.common.support.collection.wrap.Wraps;
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
        this.columnNames = Wraps.list();
        this.data = Wraps.list();
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

    /**
     * 获取名称
    */
    @Override
    public String getName() {
        return name;
    }

    /**
     * 获取column名称
    */
    @Override
    public List<String> getColumnNames() {
        return columnNames;
    }

    /**
     * 获取数据
    */
    @Override
    public List<Map<String, Object>> getData() {
        return data;
    }

    /**
     * 获取Row计算数量
    */
    @Override
    public long getRowCount() {
        return data.size();
    }
}
