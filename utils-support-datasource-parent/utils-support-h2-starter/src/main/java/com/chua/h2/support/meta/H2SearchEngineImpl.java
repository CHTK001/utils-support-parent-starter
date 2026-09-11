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
 * H2 搜索引擎实现，基于 H2 索引元数据（H2 2.x 语法）。
 * <p>
 * H2 2.x 已移除内置全文检索引擎（TEXT INDEX / CATSEARCH），
 * 本实现将检索门面降级为：普通索引管理 + 关键字 LIKE 查询。
 * </p>
 * <ul>
 *   <li>创建索引：{@code CREATE INDEX IF NOT EXISTS}</li>
 *   <li>列出索引：{@code INFORMATION_SCHEMA.INDEXES}</li>
 *   <li>关键字查询：{@code WHERE col LIKE '%keyword%'}（调用方 SQL）</li>
 * </ul>
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
            String sql = "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
                       + "WHERE INDEX_TYPE_NAME = 'INDEX'";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                List<String> indexes = new ArrayList<>();
                while (rs.next()) {
                    indexes.add(rs.getString(1));
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
                       + "WHERE UPPER(INDEX_NAME) = UPPER('" + escape(indexName) + "')";
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
        // H2 2.x 无全文检索引擎，检索门面降级为普通索引 + LIKE 关键字查询
        String sql = "CREATE INDEX IF NOT EXISTS "
                   + escape(indexName) + " ON " + escape(table)
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
        String sql = "DROP INDEX IF EXISTS " + escape(indexName);
        try (Connection conn = getConn();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("删除索引失败: " + indexName, e);
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

    /**
     * 标识符归一化：H2 未加引号标识符统一转为大写，
     * 因此这里直接大写化以匹配 H2 内部存储形式，避免引号导致的大小写敏感问题。
     */
    private static String escape(String name) {
        if (name == null) return "";
        return name.toUpperCase();
    }
}
