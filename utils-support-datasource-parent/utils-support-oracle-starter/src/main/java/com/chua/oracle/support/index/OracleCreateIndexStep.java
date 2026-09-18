package com.chua.oracle.support.index;

import com.chua.datasource.support.index.IndexManager;

import javax.sql.DataSource;

/**
* Oracle 创建索引链式步骤实现。
* <p>
* 语法：{@code CREATE INDEX 索引名 ON 表名 [USING 算法] (列名)}
* Oracle 的 使用 子句可选（默认或指定 BITMAP/BTREE 等）。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class OracleCreateIndexStep implements IndexManager.CreateIndexStep {

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

    /**
     * 构造方法，创建 Oracle创建索引Step 实例。
     *
     * @param dataSource 数据来源，不允许为 null
     * @param indexName 索引名称，不允许为 null
     */
    OracleCreateIndexStep(DataSource dataSource, String indexName) {
        this.dataSource = dataSource;
        this.indexName = indexName;
    }

    /**
    * 指定索引所属的表。
    *
    * @param table 表名
    * @return this
    */
    @Override
    public IndexManager.CreateIndexStep onTable(String table) {
        this.table = table;
        return this;
    }

    /**
    * 指定索引列。
    *
    * @param column 列名或逗号分隔的多列
    * @return this
    */
    @Override
    public IndexManager.CreateIndexStep field(String column) {
        this.column = column;
        return this;
    }

    /**
    * 指定索引列。
    *
    * @param column 列名或逗号分隔的多列
    * @return this
    */
    @Override
    public IndexManager.CreateIndexStep onColumn(String column) {
        this.column = column;
        return this;
    }

    /**
    * 指定索引算法（BITMAP / BTREE 等）。
    *
    * @param algorithm 算法名
    * @return this
    */
    @Override
    public IndexManager.CreateIndexStep type(String algorithm) {
        this.algorithm = algorithm;
        return this;
    }

    /**
    * 指定索引算法（BITMAP / BTREE 等）。
    *
    * @param algorithm 算法名
    * @return this
    */
    @Override
    public IndexManager.CreateIndexStep using(String algorithm) {
        this.algorithm = algorithm;
        return this;
    }

    /**
    * 执行 创建 索引 语句。
    */
    @Override
    public void execute() {
        StringBuilder sb = new StringBuilder("CREATE INDEX ");
        sb.append(indexName).append(" ON ").append(table);
        if (algorithm != null) {
            sb.append(" ").append(algorithm);
        }
        sb.append(" (").append(column).append(")");
        try (var c = dataSource.getConnection(); var s = c.createStatement()) {
            s.execute(sb.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
