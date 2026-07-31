package com.chua.mysql.support.index;

import com.chua.datasource.support.index.IndexManager;

import javax.sql.DataSource;
/**
 * @author CH
 */

public class MysqlCreateIndexStep implements IndexManager.CreateIndexStep {

    private final DataSource dataSource;
    private final String indexName;
    private String table;
    private String column;
    private String algorithm;

    MysqlCreateIndexStep(DataSource dataSource, String indexName) {
        this.dataSource = dataSource;
        this.indexName = indexName;
    }

    @Override
    public IndexManager.CreateIndexStep onTable(String table) {
        this.table = table;
        return this;
    }

    @Override
    public IndexManager.CreateIndexStep onColumn(String column) {
        this.column = column;
        return this;
    }

    @Override
    public IndexManager.CreateIndexStep field(String field) {
        this.column = field;
        return this;
    }

    @Override
    public IndexManager.CreateIndexStep type(String type) {
        this.algorithm = type;
        return this;
    }

    @Override
    public IndexManager.CreateIndexStep using(String algorithm) {
        this.algorithm = algorithm;
        return this;
    }

    @Override
    public void execute() {
        StringBuilder sb = new StringBuilder("CREATE INDEX ").append(indexName).append(" ON ").append(table);
        if (algorithm != null && !algorithm.isEmpty()) {
            sb.append(" USING ").append(algorithm);
        }
        sb.append(" (").append(column).append(")");
        try (var c = dataSource.getConnection();
             var s = c.createStatement()) {
            s.execute(sb.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
