package com.chua.datasource.support.meta;

import com.chua.common.support.lang.datasource.meta.SearchEngine;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * H2 搜索引擎实现，基于 H2 内置 FULLTEXT 索引能力。
 * <p>
 * H2 通过 {@code CREATE FULLTEXT INDEX} / {@code DROP FULLTEXT INDEX}
 * 管理全文索引，查询通过 {@code CATSEARCH()} 函数执行。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class H2SearchEngineImpl implements SearchEngine {

    /** 数据源 */
    private final DataSource dataSource;

    /**
     * 构造方法。
     *
     * @param dataSource H2 数据源
     */
    public H2SearchEngineImpl(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String type() {
        return "h2";
    }

    @Override
    public List<String> listIndexes() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.FULLTEXT_INDEXES")) {
            List<String> indexes = new ArrayList<>();
            while (rs.next()) {
                indexes.add(rs.getString("INDEX_NAME"));
            }
            return indexes;
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public SearchIndexDef getIndex(String indexName) {
        try (Connection conn = dataSource.getConnection()) {
            SearchIndexDef def = new SearchIndexDef();
            def.setName(indexName);
            List<SearchFieldDef> fields = new ArrayList<>();

            String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.FULLTEXT_INDEX_COLUMNS "
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
        String sql = "CREATE FULLTEXT INDEX IF NOT EXISTS "
                   + escape(indexName) + " ON TABLE " + escape(table)
                   + " (" + columnList + ")";

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("创建 H2 搜索引擎索引失败: " + indexName, e);
        }
    }

    @Override
    public boolean deleteIndex(String indexName) {
        String sql = "DROP FULLTEXT INDEX IF EXISTS " + escape(indexName);
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("删除 H2 搜索引擎索引失败: " + indexName, e);
        }
    }

    @Override
    public Object getClient() {
        return dataSource;
    }

    /** 转义标识符，防止 SQL 注入 */
    private static String escape(String name) {
        if (name == null) return "\"\"";
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
