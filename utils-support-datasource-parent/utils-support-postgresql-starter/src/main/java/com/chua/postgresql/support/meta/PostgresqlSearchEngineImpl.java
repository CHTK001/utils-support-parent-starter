package com.chua.postgresql.support.meta;

import com.chua.common.support.lang.datasource.meta.SearchEngine;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
* PostgreSQL 搜索引擎实现，基于 pg_trgm + GIN 索引。
* <p>
* 使用 {@code pg_trgm} 扩展提供模糊文本匹配，通过 GIN 索引加速查询。
* 搜索语法：{@code column % 'keyword'}（相似度 > 0.3）或
* {@code to_tsvector('simple', column) @@ to_tsquery('simple', 'keyword')}。
* </p>
*
* @author CH
* @since 4.0.0.42
* @return 列表索引的结果
 */
public class PostgresqlSearchEngineImpl implements SearchEngine {

    private final DataSource dataSource; // 数据源
/**
* postgresql搜索engineimpl。
* @param dataSource 数据源
* @return 列表索引的结果
 */

    public PostgresqlSearchEngineImpl(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String type() {
        return "postgresql";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> listIndexes() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")) {
            List<String> indexes = new ArrayList<>();
            while (rs.next()) {
                indexes.add(rs.getString("indexname"));
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
            // 查询GIN索引对应的列
            String sql = """
                    SELECT a.attname
                    FROM pg_index i
                    JOIN pg_class c ON c.oid = i.indrelid
                    JOIN pg_class ci ON ci.oid = i.indexrelid
                    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(i.indkey)
                    WHERE ci.relname = ?
                    ORDER BY a.attnum
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, indexName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        SearchFieldDef field = new SearchFieldDef();
                        field.setName(rs.getString("attname"));
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
        try (Connection conn = dataSource.getConnection()) {
            // 确保 pg_trgm 扩展存在
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            }
            // 创建 GIN 索引
            String sql = "CREATE INDEX IF NOT EXISTS `" + indexName + "` ON `" + indexName + "` "
                       + "USING gin (to_tsvector('simple', "
                       + String.join(" || ' ' || ",
                               indexDef.getFields() != null && !indexDef.getFields().isEmpty()
                                       ? indexDef.getFields().stream()
                                               .map(f -> f.getName())
                                               .toList()
                                       : List.of("*"))
                       + "))";
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
            return true;
        } catch (Exception e) {
            throw new RuntimeException("创建 PostgreSQL 搜索索引失败: " + indexName, e);
        }
    }

    @Override
    public boolean deleteIndex(String indexName) {
        String sql = "DROP INDEX IF EXISTS \"" + indexName + "\"";
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
    * 构建 tsvector @@ tsquery 搜索条件。
    *
    * @param columns 搜索列列表
    * @param keyword 关键词
    * @return SQL 条件片段
     */
    public static String tsQueryCondition(List<String> columns, String keyword) {
        if (columns == null || columns.isEmpty()) {
            return "1=0";
        }
        String concatenated = columns.stream()
                .map(c -> "to_tsvector('simple', " + c + ")")
                .reduce((a, b) -> a + " || " + b)
                .orElse("to_tsvector('simple', '')");
        String escaped = keyword.replace("&", "\\&")
                .replace("|", "\\|")
                .replace("'", "''");
        return concatenated + " @@ to_tsquery('simple', '" + escaped + "')";
    }
}
