package com.chua.h2.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.SearchEngine;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.h2.support.engine.H2Engine;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * H2 搜索引擎实现，基于 H2 FULLTEXT 索引 + CATSEARCH() 函数。
 * <p>
 * H2 全文检索通过以下方式实现：
 * <ul>
 *   <li>创建索引：{@code CREATE INDEX ... USING TEXT INDEX ON table(column)} 或 {@code CREATE FULLTEXT INDEX}</li>
 *   <li>全文查询：{@code SELECT * FROM table WHERE CATSEARCH(column, 'keyword', null) > 0}</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class H2SearchEngineImpl implements SearchEngine {

    private final H2Engine engine;

    public H2SearchEngineImpl(H2Engine engine) {
        this.engine = engine;
    }

    @Override
    public String type() {
        return "h2";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> listIndexes() {
        try (Connection conn = getConn()) {
            String sql = "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
                       + "WHERE INDEX_TYPE = 'TEXT' OR INDEX_TYPE LIKE '%TEXT%'";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                List<String> indexes = new ArrayList<>();
                while (rs.next()) {
                    indexes.add(rs.getString("INDEX_NAME"));
                }
                return indexes;
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public SearchIndexDef getIndex(String indexName) {
        try (Connection conn = getConn()) {
            SearchIndexDef def = new SearchIndexDef();
            def.setName(indexName);
            List<SearchFieldDef> fields = new ArrayList<>();

            String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                       + "WHERE INDEX_NAME = '" + escape(indexName) + "'";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    SearchFieldDef field = new SearchFieldDef();
                    field.setName(rs.getString("COLUMN_NAME"));
                    field.setType("text");
                    fields.add(field);
                }
            }
            def.setFields(fields);
            return def;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean createIndex(SearchIndexDef indexDef) {
        if (indexDef == null || indexDef.getName() == null) {
            throw new IllegalArgumentException("索引定义不能为空");
        }
        String indexName = indexDef.getName();
        String table = indexName;
        List<String> columns = new ArrayList<>();

        if (indexDef.getFields() != null && !indexDef.getFields().isEmpty()) {
            for (SearchFieldDef field : indexDef.getFields()) {
                if (field.getName() != null) {
                    columns.add(escape(field.getName()));
                }
            }
        } else {
            columns.add("*");
        }

        String columnList = String.join(", ", columns);
        // H2 全文索引：CREATE TEXT INDEX 或 CREATE INDEX ... USING TEXT INDEX
        String sql = "CREATE TEXT INDEX IF NOT EXISTS "
                   + escape(indexName) + " ON TABLE " + escape(table)
                   + " (" + columnList + ")";

        try (Connection conn = getConn();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("创建 H2 全文索引失败: " + indexName, e);
        }
    }

    @Override
    public boolean deleteIndex(String indexName) {
        String sql = "DROP TEXT INDEX IF EXISTS " + escape(indexName);
        try (Connection conn = getConn();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("删除 H2 全文索引失败: " + indexName, e);
        }
    }

    @Override
    public Object getClient() {
        return getDataSource();
    }

    /**
     * 获取 DataSource（用于全文检索查询）。
     */
    @SuppressWarnings("unchecked")
    private DataSource getDataSource() {
        Engine eng = engine;
        String defaultName = eng.getDefaultDataSourceName();
        if (defaultName != null) {
            var dsObj = eng.getDataSource(defaultName);
            if (dsObj != null) {
                return (DataSource) dsObj.getSource();
            }
        }
        return null;
    }

    private Connection getConn() throws SQLException {
        DataSource ds = getDataSource();
        if (ds == null) {
            throw new IllegalStateException("H2 搜索引擎未配置数据源");
        }
        return ds.getConnection();
    }

    private static String escape(String name) {
        if (name == null) return "\"\"";
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
