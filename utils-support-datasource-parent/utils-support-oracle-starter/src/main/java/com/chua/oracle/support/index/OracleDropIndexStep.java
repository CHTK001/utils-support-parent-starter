package com.chua.oracle.support.index;

import com.chua.datasource.support.index.IndexManager;

import javax.sql.DataSource;

/**
 * Oracle 删除索引链式步骤实现。
 * <p>
 * 语法：{@code DROP INDEX 索引名}
 * Oracle 中 DROP INDEX 不需要指定表名。
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
public class OracleDropIndexStep implements IndexManager.DropIndexStep {

    private final DataSource dataSource;
    private final String indexName;
    private String table;

    OracleDropIndexStep(DataSource dataSource, String indexName) {
        this.dataSource = dataSource;
        this.indexName = indexName;
    }

    /**
     * Oracle 中 DROP INDEX 不需要表名，但保留 onTable 方法以保持 API 一致。
     *
     * @param table 表名（Oracle 忽略此参数）
     * @return this
     */
    @Override
    public IndexManager.DropIndexStep onTable(String table) {
        this.table = table;
        return this;
    }

    /**
     * 执行 DROP INDEX 语句。
     */
    @Override
    public void execute() {
        try (var c = dataSource.getConnection(); var s = c.createStatement()) {
            s.execute("DROP INDEX " + indexName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
