package com.chua.mysql.support.index;

import com.chua.datasource.support.index.IndexManager;

import javax.sql.DataSource;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class MysqlCreateIndexStep implements IndexManager.CreateIndexStep {

    /** 数据来源 */
    private final DataSource dataSource;
    /** 索引名称 */
    private final String indexName;
    /** 表 */
    private String table;
    /** 列 */
    private String column;
    /** 算法 */
    private String algorithm;

    MysqlCreateIndexStep(DataSource dataSource, String indexName) {
        this.dataSource = dataSource;
        this.indexName = indexName;
    }

    @Override
    /** OnTable */
    public IndexManager.CreateIndexStep onTable(String table) {
        this.table = table;
        return this;
    }

    @Override
    /** OnColumn */
    public IndexManager.CreateIndexStep onColumn(String column) {
        this.column = column;
        return this;
    }

    @Override
    /** Field */
    public IndexManager.CreateIndexStep field(String field) {
        this.column = field;
        return this;
    }

    @Override
    /** Type */
    public IndexManager.CreateIndexStep type(String type) {
        this.algorithm = type;
        return this;
    }

    @Override
    /** Using */
    public IndexManager.CreateIndexStep using(String algorithm) {
        this.algorithm = algorithm;
        return this;
    }

    @Override
    /** 执行 */
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
