package com.chua.mysql.support.index;

import com.chua.datasource.support.index.IndexManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class MysqlIndexManager implements IndexManager, com.chua.datasource.support.user.DataSourceAware {

    /** 数据来源 */
    private DataSource dataSource;

    @Override
    /** Type */
    public String type() {
        return "mysql";
    }

    @Override
    /** 设置DataSource */
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    /** ListIndexes */
    public List<String> listIndexes(String table) {
        List<String> list = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SHOW INDEX FROM " + table)) {
            while (rs.next()) {
                list.add(rs.getString("Key_name"));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    @Override
    /** 创建Index */
    public CreateIndexStep createIndex(String indexName) {
        return new MysqlCreateIndexStep(dataSource, indexName);
    }

    @Override
    /** DropIndex */
    public DropIndexStep dropIndex(String indexName) {
        return new MysqlDropIndexStep(dataSource, indexName);
    }
}
