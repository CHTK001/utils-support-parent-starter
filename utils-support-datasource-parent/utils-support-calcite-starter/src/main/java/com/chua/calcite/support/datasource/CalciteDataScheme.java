package com.chua.calcite.support.datasource;

import com.chua.datasource.support.datasource.DataScheme;
import com.chua.datasource.support.datasource.DataTable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Calcite 数据方案实现类。
 * 用于封装 Calcite 的数据集结构，包含方案名称和多个数据表。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CalciteDataScheme implements DataScheme {

    /**
     * 方案名称。
     */
    private final String name;

    /**
     * 数据表列表。
     */
    private final List<DataTable> tables;

    /**
     * 构造函数，初始化方案名称和空的表列表。
     *
     * @param name 方案名称
     */
    public CalciteDataScheme(String name) {
        this.name = name;
        this.tables = new ArrayList<>();
    }

    /**
     * 添加一个数据表到方案中。
     *
     * @param table 要添加的数据表
     * @return 当前实例，支持链式调用
     */
    public CalciteDataScheme addTable(DataTable table) {
        tables.add(table);
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
     * 获取table名称
    */
    public List<String> getTableNames() {
        return tables.stream().map(DataTable::getName).collect(Collectors.toList());
    }

    @Override
    /**
     * 获取Table
    */
    public DataTable getTable(String name) {
        for (DataTable t : tables) {
            if (t.getName().equals(name)) {
                return t;
            }
        }
        return null;
    }

    @Override
    /**
     * 关闭
    */
    public void close() throws Exception {
        tables.clear();
    }
}
