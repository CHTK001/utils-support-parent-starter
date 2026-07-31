package com.chua.hibernate.support.ddl;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.table.ColumnDef;
import com.chua.common.support.lang.datasource.table.TableDef;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.ddl.DslManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Hibernate DDL 管理器，通过 JDBC 元数据读取表结构，
 * 并结合 {@link Dialect} 方言体系生成数据库感知的 DDL 语句。
 * <p>
 * 主要功能：
 * <ul>
 *   <li>通过 JDBC {@link DatabaseMetaData} 获取表定义</li>
 *   <li>自动检测数据库方言（根据 JDBC URL 匹配已注册的 Dialect SPI）</li>
 *   <li>使用方言的引用符、类型映射、DDL 语法生成跨数据库兼容的 SQL</li>
 * </ul>
 * </p>
 * <p>
 * 使用方式：
 * <pre>{@code
 * HibernateDdlManager mgr = new HibernateDdlManager();
 * mgr.setDataSource(dataSource);
 *
 * // 自动检测方言（根据 JDBC URL）
 * String ddl = mgr.createTableDDL(null, null, "user");
 *
 * // 或手动指定方言
 * mgr.setDialect(Dialect.getExtension("mysql"));
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 * @see Dialect
 * @see DslManager
 */
@Spi("hibernate")
@Slf4j
public class HibernateDdlManager implements DslManager {

    /**
     * 数据源，用于通过 JDBC 元数据读取表结构
     */
    private DataSource dataSource;

    /**
     * 数据库方言，用于生成数据库感知的 DDL；未设置时通过 JDBC URL 自动检测
     */
    private Dialect dialect;

    /**
     * 设置数据源。
     *
     * @param dataSource JDBC 数据源
     */
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 获取数据源。
     *
     * @return JDBC 数据源
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * 手动设置数据库方言。
     * <p>如果未调用此方法，{@link #createTableDDL}、{@link #renameTable} 等方法
     * 会自动通过 JDBC URL 检测方言。</p>
     *
     * @param dialect 数据库方言实例
     */
    public void setDialect(Dialect dialect) {
        this.dialect = dialect;
    }

    /**
     * 获取当前使用的方言。
     *
     * @return 方言实例（可能为 null）
     */
    public Dialect getDialect() {
        return dialect;
    }

    /**
     * 获取或自动检测方言。
     * <p>如果已手动设置方言，直接返回；否则通过 JDBC URL 自动匹配已注册的 Dialect SPI。</p>
     *
     * @return 方言实例（检测失败返回 null）
     */
    private Dialect resolveDialect() {
        if (dialect != null) {
            return dialect;
        }
        // 通过 JDBC URL 自动检测
        try (Connection conn = dataSource.getConnection()) {
            String url = conn.getMetaData().getURL();
            if (url == null || url.isEmpty()) {
                return null;
            }
            // 遍历所有已注册方言，匹配 JDBC URL 前缀
            Map<String, Dialect> all = Dialect.listAll();
            for (Map.Entry<String, Dialect> entry : all.entrySet()) {
                try {
                    String dialectUrl = entry.getValue().url();
                    // 提取协议前缀：取 jdbc:xxx 部分
                    // 兼容 jdbc:mysql:// 和 jdbc:h2:<path> 两种格式
                    String protocolKey = extractJdbcProtocol(dialectUrl);
                    if (protocolKey != null && url.startsWith(protocolKey)) {
                        log.debug("自动检测到方言: {}", entry.getKey());
                        this.dialect = entry.getValue();
                        return this.dialect;
                    }
                } catch (Exception ignored) {
                    // 单个方言解析失败不影响其他
                }
            }
            log.warn("未找到匹配的方言，URL: {}", url);
        } catch (SQLException e) {
            log.warn("自动检测方言失败", e);
        }
        return null;
    }

    /**
     * 从方言的 URL 模板中提取 JDBC 协议前缀。
     * <p>例如：
     * <ul>
     *   <li>{@code jdbc:mysql://...} → {@code jdbc:mysql}</li>
     *   <li>{@code jdbc:h2:...} → {@code jdbc:h2}</li>
     * </ul>
     * </p>
     *
     * @param dialectUrl 方言的 URL 模板
     * @return 协议前缀，解析失败返回 null
     */
    private String extractJdbcProtocol(String dialectUrl) {
        if (dialectUrl == null || !dialectUrl.startsWith("jdbc:")) {
            return null;
        }
        // 取 jdbc:xxx 部分，兼容 jdbc:mysql:// 和 jdbc:h2:path 两种格式
        int secondColon = dialectUrl.indexOf(':', 5);
        return secondColon > 0 ? dialectUrl.substring(0, secondColon) : dialectUrl;
    }

    @Override
    public TableDef getTable(String catalogName, String schemaName, String tableName) {
        TableDef def = new TableDef();
        def.setName(tableName);
        def.setSchema(schemaName);

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String actualSchema = resolveActualSchema(meta, catalogName, schemaName, tableName);
            List<ColumnDef> columns = new ArrayList<>();

            try (ResultSet rs = meta.getColumns(catalogName, actualSchema, tableName, "%")) {
                while (rs.next()) {
                    ColumnDef col = new ColumnDef();
                    col.setName(rs.getString("COLUMN_NAME"));
                    col.setType(rs.getString("TYPE_NAME"));
                    col.setLength(rs.getLong("COLUMN_SIZE"));
                    col.setNullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    col.setDefaultValue(rs.getString("COLUMN_DEF"));
                    col.setComment(rs.getString("REMARKS"));
                    columns.add(col);
                }
            }

            try (ResultSet rs = meta.getPrimaryKeys(catalogName, actualSchema, tableName)) {
                List<String> pks = new ArrayList<>();
                while (rs.next()) {
                    pks.add(rs.getString("COLUMN_NAME"));
                }
                def.setPrimaryKeys(pks.toArray(new String[0]));
                for (ColumnDef col : columns) {
                    if (pks.contains(col.getName())) {
                        col.setPrimaryKey(true);
                    }
                }
            }

            def.setColumns(columns);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return def;
    }

    private String resolveActualSchema(DatabaseMetaData meta, String catalog, String schema, String table) throws Exception {
        if (schema != null) {
            return schema;
        }
        try (ResultSet rs = meta.getTables(catalog, null, table, new String[]{"TABLE"})) {
            if (rs.next()) {
                String actual = rs.getString("TABLE_SCHEM");
                if (actual != null && !actual.isEmpty()) {
                    return actual;
                }
            }
        }
        try (ResultSet rs = meta.getTables(catalog, "", table, new String[]{"TABLE"})) {
            if (rs.next()) {
                return "";
            }
        }
        return null;
    }

    @Override
    public String createTableDDL(String catalogName, String schemaName, String tableName) {
        Dialect d = resolveDialect();
        TableDef def = getTable(catalogName, schemaName, tableName);
        if (def == null || def.getColumns() == null || def.getColumns().isEmpty()) {
            return "-- Table " + tableName + " 不存在或无法读取";
        }

        String tableNameQuoted = (d != null) ? d.quote(def.getName()) : def.getName();
        String schemaNameQuoted = (schemaName != null && !schemaName.isEmpty() && d != null)
                ? d.quote(schemaName) : schemaName;
        StringBuilder sb = new StringBuilder();

        // CREATE TABLE 关键词
        if (d != null) {
            sb.append(d.getCreateTableString());
        } else {
            sb.append("create table");
        }
        sb.append(" ").append(tableNameQuoted).append(" (\n");

        List<ColumnDef> cols = def.getColumns();
        // 收集需要在 CREATE TABLE 之后执行的独立 SQL 语句（如 PG 系方言的 COMMENT ON）
        StringBuilder afterDdlSb = new StringBuilder();

        for (int i = 0; i < cols.size(); i++) {
            ColumnDef c = cols.get(i);
            String colNameQuoted = (d != null) ? d.quote(c.getName()) : c.getName();

            sb.append("  ").append(colNameQuoted).append(" ");
            // 保留 JDBC 元数据报告的类型名 + 长度
            sb.append(c.getType());
            if (c.getLength() != null && c.getLength() > 0) {
                sb.append("(").append(c.getLength()).append(")");
            }

            // 自增
            if (c.isAutoIncrement() && d != null) {
                sb.append(" ").append(d.getAutoIncrementKeyword());
            }

            // NOT NULL
            if (!c.isNullable()) {
                sb.append(" NOT NULL");
            }

            // 默认值
            if (c.getDefaultValue() != null) {
                sb.append(" DEFAULT ").append(c.getDefaultValue());
            }

            // 主键
            if (c.isPrimaryKey()) {
                sb.append(" PRIMARY KEY");
            }

            // 列注释 — 判断方言是否支持内联注释
            if (c.getComment() != null && !c.getComment().isEmpty() && d != null) {
                if (d.supportsInlineComment()) {
                    // MySQL 兼容方言：内联追加
                    String commentSql = d.getColumnComment(c.getComment());
                    if (!commentSql.isEmpty()) {
                        sb.append(" ").append(commentSql);
                    }
                } else {
                    // PG 系方言：生成独立的 COMMENT ON 语句
                    String fullColName = schemaNameQuoted != null
                            ? schemaNameQuoted + "." + colNameQuoted
                            : colNameQuoted;
                    afterDdlSb.append("COMMENT ON COLUMN ").append(fullColName)
                            .append(" IS '").append(escapeSqlString(c.getComment())).append("';\n");
                }
            }

            if (i < cols.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append(")");

        // 表注释
        if (def.getComment() != null && !def.getComment().isEmpty() && d != null) {
            if (d.supportsInlineComment()) {
                String tableCommentSql = d.getTableComment(def.getComment());
                if (!tableCommentSql.isEmpty()) {
                    sb.append(" ").append(tableCommentSql);
                }
            } else {
                String fullName = schemaNameQuoted != null
                        ? schemaNameQuoted + "." + tableNameQuoted
                        : tableNameQuoted;
                afterDdlSb.append("COMMENT ON TABLE ").append(fullName)
                        .append(" IS '").append(escapeSqlString(def.getComment())).append("';\n");
            }
        }

        // 追加独立注释语句
        if (afterDdlSb.length() > 0) {
            sb.append(";\n").append(afterDdlSb);
        }

        return sb.toString();
    }

    /**
     * 转义 SQL 字符串中的单引号。
     *
     * @param value 原始字符串
     * @return 转义后的字符串（单引号替换为两个单引号）
     */
    private String escapeSqlString(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    @Override
    public String renameTable(String schemaName, String oldTableName, String newTableName) {
        Dialect d = resolveDialect();
        if (d != null) {
            String schema = (schemaName != null && !schemaName.isEmpty()) ? schemaName : null;
            return d.getRenameTableString(schema, oldTableName, newTableName);
        }
        // 无方言时的回退方案
        String prefix = (schemaName != null && !schemaName.isEmpty()) ? schemaName + "." : "";
        return "ALTER TABLE " + prefix + oldTableName + " RENAME TO " + prefix + newTableName;
    }

    @Override
    public String copyTableStructure(String schemaName, String sourceTableName, String targetTableName) {
        Dialect d = resolveDialect();
        String src = (d != null) ? d.quote(sourceTableName) : sourceTableName;
        String tgt = (d != null) ? d.quote(targetTableName) : targetTableName;
        return "CREATE TABLE " + tgt + " AS SELECT * FROM " + src + " WHERE 1=0";
    }

    @Override
    public List<TableDef> listTables(String catalogName, String schemaName) {
        List<TableDef> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(catalogName, schemaName, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    result.add(getTable(catalogName, schemaName, tableName));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    @Override
    public String type() {
        return "hibernate";
    }
}
