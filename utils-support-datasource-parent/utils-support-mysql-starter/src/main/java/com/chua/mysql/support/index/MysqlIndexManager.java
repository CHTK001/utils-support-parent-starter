package com.chua.mysql.support.index;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.datasource.support.index.IndexManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class MysqlIndexManager implements IndexManager, com.chua.datasource.support.user.DataSourceAware {

    /**
     * 数据来源
    */
    private DataSource dataSource;

    /**
     * 类型
    */
    @Override
    public String type() {
        return "mysql";
    }

    /**
     * 设置数据源
    */
    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 列表索引
    */
    @Override
    public List<String> listIndexes(String table) {
        List<String> list = Wraps.list();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SHOW INDEX FROM " + table)) {
            while (rs.next()) {
                list.add(rs.getString("Key_name"));
            }
        } catch (Exception e) {
            throw new RuntimeException("MySQL 索引查询失败: " + e.getMessage(), e);
        }
        return list;
    }

    /**
     * 创建索引
    */
    @Override
    public CreateIndexStep createIndex(String indexName) {
        return new MysqlCreateIndexStep(dataSource, indexName);
    }

    /**
     * 掉落索引
    */
    @Override
    public DropIndexStep dropIndex(String indexName) {
        return new MysqlDropIndexStep(dataSource, indexName);
    }
}
