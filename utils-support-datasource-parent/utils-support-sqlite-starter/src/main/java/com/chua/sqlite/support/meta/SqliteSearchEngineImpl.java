package com.chua.sqlite.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.SearchEngine;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.sqlite.support.engine.SqliteEngine;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * sqlite 搜索引擎实现，基于 FTS5 虚拟表提供索引管理。
 * <p>
 * 使用 sqlite FTS5 扩展实现全文检索索引，索引名与 FTS5 虚拟表名对应。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SqliteSearchEngineImpl implements SearchEngine {

    /**
     * FTS5 支持的虚表选项；抽象层的 settings 只映射到这些真实存在的选项，
     * 不认识的键会告警后忽略，避免"设置了却静默生效不了"。
     */
    private static final List<String> FTS5_OPTIONS =
            List.of("tokenize", "prefix", "detail", "uncompressed", "content", "content_rowid");

    /**
     * 选项取值白名单：仅允许字母、数字、下划线、点、短横线与空格，
     * 从源头排除引号、括号、分号等可以结束字符串字面量的字符。
     */
    private static final Pattern OPTION_VALUE = Pattern.compile("[A-Za-z0-9_.\\- ]{1,128}");

    /**
     * 引擎实例
    */
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
    public List<String> listIndexes() {
        try (Connection conn = getJdbcConnection()) {
            String sql = "SELECT name FROM sqlite_master WHERE type='table' AND sql LIKE '%USING fts5%'";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                List<String> indexes = new ArrayList<>();
                while (rs.next()) {
                    indexes.add(rs.getString("name"));
                }
                return indexes;
            }
        } catch (Exception e) {
            throw new IllegalStateException("列出 SQLite 搜索引擎索引失败", e);
        }
    }

    @Override
    public SearchIndexDef getIndex(String indexName) {
        try (Connection conn = getJdbcConnection()) {
            if (!isFtsTable(conn, indexName)) {
                return null;
            }
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
                    field.setType(typeStr != null && !typeStr.isEmpty() ? typeStr.toLowerCase() : "text");
                    fields.add(field);
                }
                def.setFields(fields);
                return def;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("获取 SQLite 搜索引擎索引定义失败: " + indexName, e);
        }
    }

    @Override
    public boolean createIndex(SearchIndexDef indexDef) {
        if (indexDef == null || indexDef.getName() == null || indexDef.getName().isBlank()) {
            throw new IllegalArgumentException("索引定义与索引名不能为空");
        }
        String indexName = indexDef.getName();
        List<SearchFieldDef> fields = indexDef.getFields();
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("FTS5 虚表至少需要一个文本列，索引名: " + indexName);
        }
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE VIRTUAL TABLE ").append(escapeIdentifier(indexName)).append(" USING fts5(");
        boolean first = true;
        for (SearchFieldDef field : fields) {
            if (field == null || field.getName() == null || field.getName().isBlank()) {
                throw new IllegalArgumentException("FTS5 列名不能为空, 索引名: " + indexName);
            }
            if (!first) {
                sql.append(", ");
            }
            sql.append(escapeIdentifier(field.getName()));
            if (!field.isIndexed()) {
                sql.append(" UNINDEXED");
            }
            first = false;
        }
        for (Map.Entry<String, Object> entry : checkSettings(indexName, indexDef.getSettings()).entrySet()) {
            sql.append(", ").append(entry.getKey()).append(" = ");
            Object value = entry.getValue();
            if (value instanceof Boolean bool) {
                sql.append(bool ? 1 : 0);
            } else if (value instanceof Number number) {
                sql.append(number);
            } else {
                sql.append('\'').append(value).append('\'');
            }
        }
        sql.append(")");
        try (Connection conn = getJdbcConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql.toString());
        } catch (SQLException e) {
            throw new IllegalStateException("创建 SQLite 搜索引擎索引失败: " + indexName, e);
        }
        return true;
    }

    @Override
    public boolean deleteIndex(String indexName) {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("索引名不能为空");
        }
        try (Connection conn = getJdbcConnection()) {
            boolean exists = isOrdinaryTable(conn, indexName);
            if (!exists) {
                return false;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE " + escapeIdentifier(indexName));
            }
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("删除 SQLite 搜索引擎索引失败: " + indexName, e);
        }
    }

    /**
     * 触发 FTS5 段落合并。
     * <p>
     * SQLite 没有独立的优化命令，FTS5 通过向虚表自身写入 {@code 'optimize'} 特殊行完成合并。
     * </p>
     *
     * @param indexName FTS5 虚表名
     * @return true 已执行合并；索引不存在返回 false
     * @throws IllegalArgumentException 索引名为空
     * @throws IllegalStateException    执行合并失败
     */
    public boolean optimizeIndex(String indexName) {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("索引名不能为空");
        }
        String quoted = escapeIdentifier(indexName);
        try (Connection conn = getJdbcConnection()) {
            if (!isFtsTable(conn, indexName)) {
                return false;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO " + quoted + "(" + quoted + ") VALUES('optimize')");
            }
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("优化 SQLite 搜索引擎索引失败: " + indexName, e);
        }
    }

    /**
     * 校验并归一化虚表选项。
     *
     * @param indexName 索引名，用于异常定位
     * @param settings  抽象层设置，可为空
     * @return 可安全拼入 DDL 的选项
     * @throws IllegalArgumentException 已知键的取值非法
     */
    private static Map<String, Object> checkSettings(String indexName, Map<String, Object> settings) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (settings == null || settings.isEmpty()) {
            return options;
        }
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            String key = entry.getKey();
            if (key == null || !FTS5_OPTIONS.contains(key.toLowerCase())) {
                log.warn("SQLite FTS5 不支持索引设置 {}（已忽略）, 可用设置为 {}, 索引名: {}",
                        key, FTS5_OPTIONS, indexName);
                continue;
            }
            Object value = entry.getValue();
            if ("uncompressed".equalsIgnoreCase(key)) {
                options.put(key, toUncompressed(value, indexName));
                continue;
            }
            String text = value == null ? null : String.valueOf(value);
            if (text == null || (!text.isEmpty() && !OPTION_VALUE.matcher(text).matches())) {
                throw new IllegalArgumentException("索引设置取值非法: " + key + "=" + value
                        + ", 索引名: " + indexName);
            }
            options.put(key, text);
        }
        return options;
    }

    /**
     * FTS5 的 uncompressed 只接受 0/1 整数字面量。
     *
     * @param value     原始取值
     * @param indexName 索引名，用于异常定位
     * @return 0 或 1
     * @throws IllegalArgumentException 取值非 0/1
     */
    private static int toUncompressed(Object value, String indexName) {
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        String text = value == null ? "" : String.valueOf(value);
        if ("1".equals(text) || Boolean.TRUE.toString().equalsIgnoreCase(text)) {
            return 1;
        }
        if ("0".equals(text) || Boolean.FALSE.toString().equalsIgnoreCase(text)) {
            return 0;
        }
        throw new IllegalArgumentException("uncompressed 取值只能是 0 或 1, 当前: " + value + ", 索引名: " + indexName);
    }

    /**
     * 判断给定名称是否为已建成的 FTS5 虚表。
     *
     * @param conn JDBC 连接
     * @param name 表名
     * @return 是 FTS5 虚表返回 true
     * @throws SQLException 查询失败
     */
    private static boolean isFtsTable(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? AND sql LIKE '%USING fts5%'")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 判断给定名称是否存在于 sqlite_master 的普通表中（用于删除时区分"不存在"）。
     *
     * @param conn JDBC 连接
     * @param name 表名
     * @return 表存在返回 true
     * @throws SQLException 查询失败
     */
    private static boolean isOrdinaryTable(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
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
    static String escapeIdentifier(String name) {
        if (name == null) {
            return "\"\"";
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
