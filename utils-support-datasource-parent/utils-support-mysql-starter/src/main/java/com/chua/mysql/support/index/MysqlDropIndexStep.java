package com.chua.mysql.support.index;

import com.chua.datasource.support.index.IndexManager;

import javax.sql.DataSource;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class MysqlDropIndexStep implements IndexManager.DropIndexStep {

    private final DataSource dataSource;
    private final String indexName;
    private String table;

    MysqlDropIndexStep(DataSource dataSource, String indexName) {
        this.dataSource = dataSource;
        this.indexName = indexName;
    }

    @Override
    public IndexManager.DropIndexStep onTable(String table) {
        this.table = table;
        return this;
    }

    @Override
    public void execute() {
        try (var c = dataSource.getConnection();
             var s = c.createStatement()) {
            s.execute("DROP INDEX " + indexName + " ON " + table);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
