package com.chua.sqlite.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.SearchEngine;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.sqlite.support.engine.SqliteEngine;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * sqlite 搜索引擎实现，基于 FTS5 虚拟表提供索引管理。
 * <p>
 * 使用 sqlite FTS5 扩展实现全文检索索引，索引名与 FTS5 虚拟表名对应。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SqliteSearchEngineImpl implements SearchEngine {

    /** 引擎实例 */
    private final SqliteEngine engine;

    /**
     * 构造方法。
     * 持有 SQLite 引擎实例，用于获取 JDBC 连接与数据源。
     *
     * @param engine SQLite 引擎实例，不能为空
     */
    public SqliteSearchEngineImpl(SqliteEngine engine) {
        this.engine = engine;
    }

    @Override
    public String type() {
        return "sqlite";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> listIndexes() {
        try (Connection conn = getJdbcConnection()) {
            String sql = "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'fts_%'";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                List<String> indexes = new ArrayList<>();
                while (rs.next()) {
                    indexes.add(rs.getString("name"));
                }
                return indexes;
            }
        } catch (Exception e) {
            throw new RuntimeException("列出 SQLite 搜索引擎索引失败", e);
        }
    }

    @Override
    public SearchIndexDef getIndex(String indexName) {
        try (Connection conn = getJdbcConnection()) {
            String sql = "PRAGMA table_info(" + escapeIdentifier(indexName) + ")";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                SearchIndexDef def = new SearchIndexDef();
                def.setName(indexName);
                List<SearchFieldDef> fields = new ArrayList<>();
                while (rs.next()) {
                    SearchFieldDef field = new SearchFieldDef();
                    field.setName(rs.getString("name"));
                    String typeStr = rs.getString("type");
                    field.setType(typeStr != null ? typeStr.toLowerCase() : "text");
                    fields.add(field);
                }
                def.setFields(fields);
                return def;
            }
        } catch (Exception e) {
            throw new RuntimeException("获取 SQLite 搜索引擎索引定义失败: " + indexName, e);
        }
    }

    @Override
    public boolean createIndex(SearchIndexDef indexDef) {
        if (indexDef == null || indexDef.getName() == null) {
            throw new IllegalArgumentException("索引定义不能为空");
        }
        String indexName = indexDef.getName();
        try (Connection conn = getJdbcConnection()) {
            conn.setAutoCommit(true);
            Statement stmt = conn.createStatement();

            StringBuilder sql = new StringBuilder();
            sql.append("CREATE VIRTUAL TABLE ");
            sql.append(escapeIdentifier(indexName)).append(" USING fts5(");

            List<String> fieldNames = new ArrayList<>();
            if (indexDef.getFields() != null && !indexDef.getFields().isEmpty()) {
                for (SearchFieldDef field : indexDef.getFields()) {
                    if (!fieldNames.isEmpty()) {
                        sql.append(", ");
                    }
                    sql.append(escapeIdentifier(field.getName()));
                    fieldNames.add(field.getName());
                }
            } else {
                sql.append("*");
            }
            sql.append(")");

            stmt.execute(sql.toString());
            stmt.close();

            return true;
        } catch (Exception e) {
            throw new RuntimeException("创建 SQLite 搜索引擎索引失败: " + indexName, e);
        }
    }

    @Override
    public boolean deleteIndex(String indexName) {
        try (Connection conn = getJdbcConnection()) {
            conn.setAutoCommit(true);
            Statement stmt = conn.createStatement();
            stmt.execute("DROP TABLE IF EXISTS " + escapeIdentifier(indexName));
            stmt.close();
            return true;
        } catch (Exception e) {
            throw new RuntimeException("删除 SQLite 搜索引擎索引失败: " + indexName, e);
        }
    }

    @Override
    public Object getClient() {
        DataSource ds = getDataSource();
        return ds;
    }

    /**
     * 获取默认数据源。
     * @return 获取数据源的结果
     */
    @SuppressWarnings("unchecked")
    private DataSource getDataSource() {
        Engine engine = this.engine;
        var dsObj = engine.getDataSource();
        if (dsObj == null) {
            throw new IllegalStateException("SQLite 搜索引擎未配置数据源");
        }
        return (DataSource) dsObj.getSource();
    }

    /**
     * 获取 JDBC 连接。
     * @return 获取jdbcconnection的结果
     */
    private Connection getJdbcConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    /**
     * 转义标识符（表名/列名），防止 SQL 注入。
     * @param name 名称
     * @return escapeIdentifier的结果
     */
    private static String escapeIdentifier(String name) {
        if (name == null) {
            return "\"\"";
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
