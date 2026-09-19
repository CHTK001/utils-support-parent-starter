package com.chua.h2.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.SearchEngine;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.h2.support.engine.H2Engine;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * H2 搜索引擎实现，基于 H2 索引元数据（H2 2.x 语法）。
 * <p>
 * H2 2.x 已移除内置全文检索引擎，本实现把检索门面落地为：
 * </p>
 * <ul>
 *   <li>创建索引：{@code CREATE INDEX IF NOT EXISTS 索引名 ON 同名表 (索引列)}，
 *       即索引名同时是被索引表名，表必须已存在</li>
 *   <li>列出索引：{@code INFORMATION_SCHEMA.INDEXES}（仅用户创建的普通二级索引，
 *       排除主键与约束自动生成的索引）</li>
 *   <li>维护索引：{@code ANALYZE}（H2 2.x 唯一的统计信息重算命令，作用于整个库）</li>
 *   <li>关键字查询：{@code WHERE col LIKE '%keyword%'}（由调用方 SQL 执行）</li>
 * </ul>
 * <p>
 * 引擎不存在近实时可见性窗口：写入随事务提交即可被检索，因此没有刷新命令。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class H2SearchEngineImpl implements SearchEngine {

    /**
     * 索引存在性探测语句，绑定索引名参数
     */
    private static final String INDEX_EXISTS_SQL =
            "SELECT 1 FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_TYPE_NAME = 'INDEX' "
                    + "AND UPPER(INDEX_NAME) = UPPER(?)";

    /**
     * 索引列及其真实列类型查询，绑定索引名参数
     */
    private static final String INDEX_COLUMNS_SQL =
            "SELECT ic.COLUMN_NAME, c.DATA_TYPE FROM INFORMATION_SCHEMA.INDEX_COLUMNS ic "
                    + "LEFT JOIN INFORMATION_SCHEMA.COLUMNS c "
                    + "ON c.TABLE_CATALOG = ic.TABLE_CATALOG AND c.TABLE_SCHEMA = ic.TABLE_SCHEMA "
                    + "AND c.TABLE_NAME = ic.TABLE_NAME AND c.COLUMN_NAME = ic.COLUMN_NAME "
                    + "WHERE UPPER(ic.INDEX_NAME) = UPPER(?) ORDER BY ic.ORDINAL_POSITION";

    /**
     * 引擎实例
     */
    private final H2Engine engine;

    /**
     * 构造方法。
     * 持有 H2 引擎实例，用于获取 JDBC 连接与数据源。
     *
     * @param engine H2 引擎实例，不能为空
     */
    public H2SearchEngineImpl(H2Engine engine) {
        this.engine = engine;
    }

    @Override
    public String type() {
        return "h2";
    }

    @Override
    public List<String> listIndexes() {
        String sql = "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
                + "WHERE INDEX_TYPE_NAME = 'INDEX'";
        try (Connection conn = getConn();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> indexes = new ArrayList<>();
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
            return indexes;
        } catch (SQLException e) {
            throw new IllegalStateException("列出 H2 搜索引擎索引失败", e);
        }
    }

    @Override
    public SearchIndexDef getIndex(String indexName) {
        requireIndexName(indexName);
        try (Connection conn = getConn()) {
            if (!isUserIndex(conn, indexName)) {
                return null;
            }
            SearchIndexDef def = new SearchIndexDef();
            def.setName(indexName);
            def.setFields(indexColumns(conn, indexName));
            return def;
        } catch (SQLException e) {
            throw new IllegalStateException("获取 H2 搜索引擎索引定义失败: " + indexName, e);
        }
    }

    @Override
    public boolean createIndex(SearchIndexDef indexDef) {
        if (indexDef == null || indexDef.getName() == null || indexDef.getName().isBlank()) {
            throw new IllegalArgumentException("索引定义与索引名不能为空");
        }
        String indexName = indexDef.getName();
        List<String> columns = new ArrayList<>();
        if (indexDef.getFields() != null) {
            for (SearchFieldDef field : indexDef.getFields()) {
                if (field == null) {
                    continue;
                }
                if (field.getName() == null || field.getName().isBlank()) {
                    throw new IllegalArgumentException("索引列名不能为空, 索引名: " + indexName);
                }
                if (!field.isIndexed()) {
                    continue;
                }
                columns.add(field.getName());
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("H2 普通索引至少需要一个索引列, 索引名: " + indexName);
        }
        // H2 2.x 无全文检索引擎，检索门面降级为同名表上的普通索引 + LIKE 关键字查询
        List<String> quoted = new ArrayList<>();
        for (String column : columns) {
            quoted.add(escapeIdentifier(column));
        }
        String sql = "CREATE INDEX IF NOT EXISTS " + escapeIdentifier(indexName)
                + " ON " + escapeIdentifier(indexName) + " (" + String.join(", ", quoted) + ")";
        try (Connection conn = getConn()) {
            List<SearchFieldDef> existing = isUserIndex(conn, indexName) ? indexColumns(conn, indexName) : List.of();
            if (!existing.isEmpty()) {
                if (sameColumns(existing, columns)) {
                    return true;
                }
                // IF NOT EXISTS 会静默保留旧索引，不报错就等于谎报建索引成功
                List<String> existingNames = new ArrayList<>();
                for (SearchFieldDef field : existing) {
                    existingNames.add(field.getName());
                }
                throw new IllegalStateException("H2 已存在同名索引 " + indexName + "，其索引列为 "
                        + String.join(", ", existingNames) + "，与本次请求的 " + String.join(", ", columns)
                        + " 不一致；请先删除该索引或改用其它索引名");
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("创建 H2 搜索引擎索引失败: " + indexName, e);
        }
    }

    @Override
    public boolean deleteIndex(String indexName) {
        requireIndexName(indexName);
        try (Connection conn = getConn()) {
            if (!isUserIndex(conn, indexName)) {
                return false;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP INDEX IF EXISTS " + escapeIdentifier(indexName));
            }
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("删除 H2 搜索引擎索引失败: " + indexName, e);
        }
    }

    /**
     * 判断索引是否存在。
     * <p>
     * 仅识别用户创建的普通二级索引，主键与唯一约束自动生成的索引不计入。
     * </p>
     *
     * @param indexName 索引名，不能为空
     * @return 索引存在返回 true
     * @throws IllegalArgumentException 索引名为空
     * @throws IllegalStateException    查询失败
     */
    public boolean indexExists(String indexName) {
        requireIndexName(indexName);
        try (Connection conn = getConn()) {
            return isUserIndex(conn, indexName);
        } catch (SQLException e) {
            throw new IllegalStateException("检查 H2 搜索引擎索引是否存在失败: " + indexName, e);
        }
    }

    /**
     * 重算统计信息以优化查询计划。
     * <p>
     * H2 2.x 取消了 {@code ANALYZE 表名} 与 {@code ALTER TABLE ... ANALYZE} 语法，
     * 只保留库级 {@code ANALYZE} 命令；本方法先确认索引存在，再执行该命令。
     * H2 索引随 DML 自动维护，不存在段合并一类的操作。
     * </p>
     *
     * @param indexName 索引名，不能为空
     * @return 索引存在并完成统计信息重算返回 true；索引不存在返回 false
     * @throws IllegalArgumentException 索引名为空
     * @throws IllegalStateException    执行命令失败
     */
    public boolean analyzeIndex(String indexName) {
        requireIndexName(indexName);
        try (Connection conn = getConn()) {
            if (!isUserIndex(conn, indexName)) {
                return false;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ANALYZE");
            }
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("优化 H2 搜索引擎索引失败: " + indexName, e);
        }
    }

    @Override
    public Object getClient() {
        return getDataSource();
    }

    /**
     * 校验索引名。
     *
     * @param indexName 索引名
     * @throws IllegalArgumentException 索引名为空
     */
    private static void requireIndexName(String indexName) {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("索引名不能为空");
        }
    }

    /**
     * 读取索引的列及其真实列类型。
     *
     * @param conn      JDBC 连接
     * @param indexName 索引名
     * @return 按 ORDINAL_POSITION 排序的索引列，索引不存在时返回空列表
     * @throws SQLException 查询失败
     */
    private static List<SearchFieldDef> indexColumns(Connection conn, String indexName) throws SQLException {
        List<SearchFieldDef> fields = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(INDEX_COLUMNS_SQL)) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SearchFieldDef field = new SearchFieldDef();
                    field.setName(rs.getString("COLUMN_NAME"));
                    field.setType(normalizeColumnType(rs.getString("DATA_TYPE")));
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    /**
     * 判断已存在的索引列是否与请求的索引列等价。
     * <p>
     * H2 未加引号标识符统一存为大写，因此比较时忽略大小写与引号，且不计较列顺序。
     * </p>
     *
     * @param existing  库中已有的索引列
     * @param requested 本次请求的索引列
     * @return 列集合完全一致返回 true
     */
    private static boolean sameColumns(List<SearchFieldDef> existing, List<String> requested) {
        if (existing.size() != requested.size()) {
            return false;
        }
        List<String> current = new ArrayList<>();
        for (SearchFieldDef field : existing) {
            current.add(normalizeName(field.getName()));
        }
        List<String> wanted = new ArrayList<>();
        for (String name : requested) {
            wanted.add(normalizeName(name));
        }
        current.sort(null);
        wanted.sort(null);
        return current.equals(wanted);
    }

    /**
     * 归一化标识符用于比较。
     *
     * @param name 标识符
     * @return 去掉引号并转大写后的标识符
     */
    private static String normalizeName(String name) {
        return name == null ? "" : name.replace("\"", "").toUpperCase(Locale.ROOT);
    }

    /**
     * 判断给定名称是否为 H2 用户创建的普通二级索引。
     *
     * @param conn JDBC 连接
     * @param name 索引名
     * @return 索引存在返回 true
     * @throws SQLException 查询失败
     */
    private static boolean isUserIndex(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INDEX_EXISTS_SQL)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 将 H2 列类型归一化为检索抽象层的类型名。
     * <p>
     * 返回的类型取自被索引表的真实列类型，H2 索引本身不存储类型信息。
     * </p>
     *
     * @param dataType H2 {@code INFORMATION_SCHEMA.COLUMNS.DATA_TYPE} 取值
     * @return 归一化后的类型名，未知类型原样小写返回
     */
    private static String normalizeColumnType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return "text";
        }
        String type = dataType.trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "CHARACTER", "CHARACTER VARYING", "VARCHAR", "VARCHAR2", "NCHAR", "NVARCHAR", "CLOB",
                    "CHARACTER LARGE OBJECT", "CHARACTER LARGE OBJECT VARYING", "JSON", "UUID" -> "text";
            case "TINYINT", "SMALLINT", "INTEGER", "INT", "INT4", "SERIAL" -> "integer";
            case "BIGINT", "BIGSERIAL", "INT8" -> "long";
            case "REAL", "FLOAT4" -> "float";
            case "FLOAT", "DOUBLE", "DOUBLE PRECISION", "FLOAT8", "NUMERIC", "DECIMAL", "DECFLOAT" -> "double";
            case "BOOLEAN", "BOOL" -> "boolean";
            case "DATE", "TIME", "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "TIME WITH TIME ZONE" -> "date";
            default -> type.toLowerCase(Locale.ROOT);
        };
    }

    /**
     * 获取默认数据源（用于检索查询）。
     *
     * @return 数据源，未配置默认数据源时返回 {@code null}
     */
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

    /**
     * 获取连接。
     *
     * @return 连接对象
     * @throws SQLException     获取连接失败
     * @throws IllegalStateException 未配置数据源
     */
    private Connection getConn() throws SQLException {
        DataSource ds = getDataSource();
        if (ds == null) {
            throw new IllegalStateException("H2 搜索引擎未配置数据源");
        }
        return ds.getConnection();
    }

    /**
     * 转义标识符（表名/索引名/列名）。
     * <p>
     * H2 未加引号标识符统一转为大写，这里先大写归一再整体加引号，
     * 内部双引号成对转义，避免标识符携带引号或分号时改写 DDL。
     * </p>
     *
     * @param name 名称
     * @return 可安全拼入 DDL 的标识符
     */
    static String escapeIdentifier(String name) {
        if (name == null) {
            return "\"\"";
        }
        String normalized = name.toUpperCase(Locale.ROOT).replace("\"", "\"\"");
        return "\"" + normalized + "\"";
    }
}
