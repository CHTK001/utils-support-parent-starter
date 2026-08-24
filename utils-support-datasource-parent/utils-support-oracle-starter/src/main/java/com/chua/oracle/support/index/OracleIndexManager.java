package com.chua.oracle.support.index;

import com.chua.datasource.support.index.IndexManager;
import com.chua.datasource.support.user.DataSourceAware;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Oracle 索引管理器 SPI 实现。
 * <p>
 * Oracle 索引管理使用 user_indexes 视图查询当前用户的索引列表。
 * 创建索引使用 CREATE INDEX，删除使用 DROP INDEX。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OracleIndexManager implements IndexManager, DataSourceAware {

    /** 数据来源 */
    private DataSource dataSource;

    /**
     * 返回 SPI 扩展键：{@code oracle}
     *
     * @return "oracle"
     */
    @Override
    public String type() {
        return "oracle";
    }

    /**
     * 设置 JDBC 数据源，由 SPI 工厂自动调用。
     *
     * @param dataSource 数据源
     */
    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 查询指定表上的所有索引（通过 user_indexes 视图）。
     *
     * @param table 表名（自动转换为大写）
     * @return 索引名列表
     */
    @Override
    public List<String> listIndexes(String table) {
        List<String> list = new ArrayList<>();
        String upperTable = table.toUpperCase();
        try (Connection c = dataSource.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT index_name FROM user_indexes WHERE table_name = ?")) {
            ps.setString(1, upperTable);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString("index_name"));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    /**
     * 创建索引的链式构建器。
     *
     * @param indexName 索引名
     * @return 创建索引的链式步骤对象
     */
    @Override
    public CreateIndexStep createIndex(String indexName) {
        return new OracleCreateIndexStep(dataSource, indexName);
    }

    /**
     * 删除索引的链式构建器。
     *
     * @param indexName 索引名
     * @return 删除索引的链式步骤对象
     */
    @Override
    public DropIndexStep dropIndex(String indexName) {
        return new OracleDropIndexStep(dataSource, indexName);
    }
}
