package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.common.support.lang.datasource.meta.SearchEngine;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MySQL 搜索引擎实现，把「索引」映射到表上的 FULLTEXT 索引。
 * <p>
 * 与 SQLite 的 FTS5 虚表不同，MySQL 的全文索引必须挂在已有表上，因此
 * {@link SearchIndexDef#getName()} 是索引名，目标表由 {@code settings} 里的
 * {@link #SETTING_TABLE} 指定；可选的 {@link #SETTING_PARSER} 映射到
 * {@code WITH PARSER}（中文分词常用 {@code ngram}）。
 * </p>
 * <p>
 * 检索由 {@link #matchAgainstSql(List, String)} 生成 {@code MATCH ... AGAINST} 片段。
 * 所有标识符先经 {@link SqlName} 白名单校验再加反引号，取值只走占位符。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MysqlSearchEngineImpl implements SearchEngine {

    /**
     * 索引所属表，写入 {@code settings}，创建全文索引时必填
     */
    public static final String SETTING_TABLE = "table";

    /**
     * 全文解析器（如 {@code ngram}），对应 {@code WITH PARSER}，可选
     */
    public static final String SETTING_PARSER = "parser";

    /**
     * MySQL 内置与常见插件解析器白名单
     */
    private static final Pattern PARSER = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    /**
     * 数据源
     */
    private final DataSource dataSource;

    /**
     * 构造方法。
     *
     * @param dataSource MySQL 数据源，不能为空
     */
    public MysqlSearchEngineImpl(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("MySQL 搜索引擎需要数据源");
        }
        this.dataSource = dataSource;
    }

    @Override
    public String type() {
        return "mysql";
    }

    @Override
    public List<String> listIndexes() {
        String sql = "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS "
                + "WHERE INDEX_TYPE = 'FULLTEXT' AND TABLE_SCHEMA = DATABASE() "
                + "ORDER BY INDEX_NAME";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            List<String> indexes = new ArrayList<>();
            while (rs.next()) {
                indexes.add(rs.getString("INDEX_NAME"));
            }
            return indexes;
        } catch (SQLException e) {
            throw new IllegalStateException("列出 MySQL 全文索引失败", e);
        }
    }

    @Override
    public SearchIndexDef getIndex(String indexName) {
        SqlName.check(indexName, "索引名");
        String sql = "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS "
                + "WHERE INDEX_TYPE = 'FULLTEXT' AND TABLE_SCHEMA = DATABASE() AND INDEX_NAME = ? "
                + "ORDER BY TABLE_NAME, SEQ_IN_INDEX";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String table = rs.getString("TABLE_NAME");
                List<SearchFieldDef> fields = new ArrayList<>();
                do {
                    String other = rs.getString("TABLE_NAME");
                    if (!table.equals(other)) {
                        throw new IllegalStateException("全文索引名 " + indexName
                                + " 同时存在于表 " + table + " 与 " + other + "，请按表检索");
                    }
                    SearchFieldDef field = new SearchFieldDef();
                    field.setName(rs.getString("COLUMN_NAME"));
                    field.setType("text");
                    fields.add(field);
                } while (rs.next());
                SearchIndexDef def = new SearchIndexDef();
                def.setName(indexName);
                def.setFields(fields);
                Map<String, Object> settings = new LinkedHashMap<>();
                settings.put(SETTING_TABLE, table);
                def.setSettings(settings);
                return def;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("获取 MySQL 全文索引定义失败: " + indexName, e);
        }
    }

    @Override
    public boolean createIndex(SearchIndexDef indexDef) {
        if (indexDef == null || indexDef.getName() == null || indexDef.getName().isBlank()) {
            throw new IllegalArgumentException("索引定义与索引名不能为空");
        }
        String indexName = indexDef.getName();
        List<SearchFieldDef> source = indexDef.getFields();
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("MySQL 全文索引至少需要一个列, 索引名: " + indexName);
        }
        StringBuilder columns = new StringBuilder();
        for (SearchFieldDef field : source) {
            if (field == null || field.getName() == null || field.getName().isBlank()) {
                throw new IllegalArgumentException("全文索引列名不能为空, 索引名: " + indexName);
            }
            if (!field.isIndexed()) {
                log.warn("MySQL 全文索引没有列级开关，列 {} 仍会被索引, 索引名: {}", field.getName(), indexName);
            }
            if (columns.length() > 0) {
                columns.append(", ");
            }
            columns.append(SqlName.quoteMysql(field.getName(), "列名"));
        }
        StringBuilder sql = new StringBuilder("CREATE FULLTEXT INDEX ")
                .append(SqlName.quoteMysql(indexName, "索引名"))
                .append(" ON ").append(SqlName.quoteMysql(requireTable(indexDef), "索引所属表"))
                .append(" (").append(columns).append(")");
        String parser = parserOf(indexDef);
        if (parser != null) {
            sql.append(" WITH PARSER ").append(parser);
        }
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql.toString());
        } catch (SQLException e) {
            throw new IllegalStateException("创建 MySQL 全文索引失败: " + indexName + ", 语句: " + sql, e);
        }
        return true;
    }

    @Override
    public boolean deleteIndex(String indexName) {
        SqlName.check(indexName, "索引名");
        SearchIndexDef def = getIndex(indexName);
        if (def == null) {
            return false;
        }
        String table = String.valueOf(def.getSettings().get(SETTING_TABLE));
        String sql = "DROP INDEX " + SqlName.quoteMysql(indexName, "索引名")
                + " ON " + SqlName.quoteMysql(table, "索引所属表");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("删除 MySQL 全文索引失败: " + indexName + " ON " + table, e);
        }
    }

    /**
     * 合并该索引所在表的全文倒排段。
     * <p>
     * MySQL 没有针对单个全文索引的优化命令，{@code OPTIMIZE TABLE} 会重建表的全文索引并清理删除标记。
     * </p>
     *
     * @param indexName 索引名
     * @return true 已执行优化；索引不存在返回 false
     * @throws IllegalArgumentException 索引名非法
     * @throws IllegalStateException    优化语句执行失败
     */
    public boolean optimizeIndex(String indexName) {
        SearchIndexDef def = getIndex(indexName);
        if (def == null) {
            return false;
        }
        String table = String.valueOf(def.getSettings().get(SETTING_TABLE));
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("OPTIMIZE TABLE " + SqlName.quoteMysql(table, "索引所属表"));
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("优化 MySQL 全文索引失败: " + indexName + " ON " + table, e);
        }
    }

    @Override
    public Object getClient() {
        return dataSource;
    }

    /**
     * 生成 {@code MATCH ... AGAINST} 检索片段（自然语言模式）。
     *
     * @param columns 参与检索的列名，必须都是合法标识符
     * @param keyword 检索词，不能为空
     * @return 形如 {@code MATCH(`a`, `b`) AGAINST ('kw' IN NATURAL LANGUAGE MODE)} 的布尔表达式
     * @throws IllegalArgumentException 列为空、列名非法或检索词为空
     */
    public static String matchAgainstSql(List<String> columns, String keyword) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("MATCH AGAINST 至少需要一列");
        }
        if (keyword == null || keyword.isEmpty()) {
            throw new IllegalArgumentException("MATCH AGAINST 检索词不能为空");
        }
        StringBuilder cols = new StringBuilder();
        for (String column : columns) {
            if (cols.length() > 0) {
                cols.append(", ");
            }
            cols.append(SqlName.quoteMysql(column, "列名"));
        }
        return "MATCH(" + cols + ") AGAINST ('" + escapeLiteral(keyword) + "' IN NATURAL LANGUAGE MODE)";
    }

    /**
     * 转义字符串字面量：单引号翻倍，反斜杠翻倍以兼容默认未开启 NO_BACKSLASH_ESCAPES 的实例。
     *
     * @param keyword 原始检索词
     * @return 可安全嵌入单引号的片段
     */
    private static String escapeLiteral(String keyword) {
        return keyword.replace("\\", "\\\\").replace("'", "''");
    }

    /**
     * 取出并校验索引所属表。
     *
     * @param indexDef 索引定义
     * @return 合法表名
     * @throws IllegalArgumentException 未提供或非法
     */
    private static String requireTable(SearchIndexDef indexDef) {
        Map<String, Object> settings = indexDef.getSettings();
        Object table = settings == null ? null : settings.get(SETTING_TABLE);
        if (table == null || String.valueOf(table).isBlank()) {
            throw new IllegalArgumentException("MySQL 全文索引必须挂在已有表上，请在 settings 里提供 "
                    + SETTING_TABLE + ", 索引名: " + indexDef.getName());
        }
        return SqlName.check(String.valueOf(table), "索引所属表");
    }

    /**
     * 取出并校验可选的全文解析器，同时提示无法被 MySQL 落实的其它设置。
     *
     * @param indexDef 索引定义
     * @return 解析器名，未指定返回 null
     * @throws IllegalArgumentException 解析器名非法
     */
    private static String parserOf(SearchIndexDef indexDef) {
        Map<String, Object> settings = indexDef.getSettings();
        if (settings == null || settings.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            String key = entry.getKey();
            if (!SETTING_TABLE.equals(key) && !SETTING_PARSER.equals(key)) {
                log.warn("MySQL 全文索引不支持设置 {}（已忽略）, 可用设置为 {} 与 {}, 索引名: {}",
                        key, SETTING_TABLE, SETTING_PARSER, indexDef.getName());
            }
        }
        Object raw = settings.get(SETTING_PARSER);
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        String parser = String.valueOf(raw);
        if (!PARSER.matcher(parser).matches()) {
            throw new IllegalArgumentException("全文解析器名非法: " + parser
                    + ", 索引名: " + indexDef.getName());
        }
        return parser;
    }
}
