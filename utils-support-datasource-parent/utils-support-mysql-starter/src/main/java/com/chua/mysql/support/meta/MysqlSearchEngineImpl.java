package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.meta.SearchEngine;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
* MySQL 搜索引擎实现，基于 FULLTEXT 索引。
* <p>
* MySQL 8.0+ 支持 {@code CREATE FULLTEXT INDEX}，查询使用
* {@code MATCH(col) AGAINST('keyword' IN NATURAL LANGUAGE MODE)}。
* </p>
*
* @author CH
* @since 4.0.0.42
* @return 列表索引的结果
 */
public class MysqlSearchEngineImpl implements SearchEngine {

    private final DataSource dataSource; // 数据源
/**
* mysql搜索engineimpl。
* @param dataSource 数据源
* @return 列表索引的结果
 */

    public MysqlSearchEngineImpl(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String type() {
        return "mysql";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> listIndexes() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS "
                     + "WHERE NON_UNIQUE = 0 AND INDEX_TYPE = 'FULLTEXT'")) {
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
            String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS "
                       + "WHERE INDEX_NAME = ? ORDER BY SEQ_IN_INDEX";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, indexName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        SearchFieldDef field = new SearchFieldDef();
                        field.setName(rs.getString("COLUMN_NAME"));
                        field.setType("text");
                        fields.add(field);
                    }
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
                    columns.add("`" + field.getName() + "`");
                }
            }
        } else {
            // 默认对整个表的所有文本列建全文索引（MySQL 8.0.17+ 支持生成列）
            columns.add("*");
        }
        String columnList = String.join(", ", columns);
        String sql;
        if (columns.contains("*")) {
            // MySQL 8.0.17+ 支持对整个表的全文索引
            sql = "ALTER TABLE `" + indexName + "` ADD FULLTEXT INDEX `" + indexName + "` ("
                + String.join(", ", columns) + ")";
        } else {
            sql = "CREATE FULLTEXT INDEX `" + indexName + "` ON `" + indexName + "` (" + columnList + ")";
        }
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("创建 MySQL 全文索引失败: " + indexName, e);
        }
    }

    @Override
    public boolean deleteIndex(String indexName) {
        String sql = "DROP FULLTEXT INDEX `" + indexName + "` ON `" + indexName + "`";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Object getClient() {
        return dataSource;
    }

    /**
    * 构建 匹配...AGAINST 查询片段。
    *
    * @param columns  要搜索的列名列表
    * @param keyword  搜索关键词
    * @return SQL 片段，如 {@code MATCH(col1, col2) AGAINST ('keyword' IN NATURAL LANGUAGE MODE)}
     */
    public static String matchAgainstSql(List<String> columns, String keyword) {
        if (columns == null || columns.isEmpty()) {
            return "1=0";
        }
        String cols = String.join(", ", columns);
        String escaped = keyword.replace("'", "''");
        return "MATCH(" + cols + ") AGAINST ('" + escaped + "' IN NATURAL LANGUAGE MODE) > 0";
    }
}
