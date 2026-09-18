package com.chua.mysql.support.index;

import com.chua.datasource.support.index.IndexManager;

import javax.sql.DataSource;
/**
* @author CH
* @since 4.0.0.42
 */

public class MysqlDropIndexStep implements IndexManager.DropIndexStep {

    /** 数据来源 */
    private final DataSource dataSource;
    /** 索引名称 */
    private final String indexName;
    /** 表 */
    private String table;

    /**
     * 构造方法，创建 Mysql删除索引Step 实例。
     *
     * @param dataSource 数据来源，不允许为 null
     * @param indexName 索引名称，不允许为 null
     */
    MysqlDropIndexStep(DataSource dataSource, String indexName) {
        this.dataSource = dataSource;
        this.indexName = indexName;
    }

    @Override
    /** ontable */
    public IndexManager.DropIndexStep onTable(String table) {
        this.table = table;
        return this;
    }

    @Override
    /** 执行 */
    public void execute() {
        try (var c = dataSource.getConnection();
             var s = c.createStatement()) {
            s.execute("DROP INDEX " + indexName + " ON " + table);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
