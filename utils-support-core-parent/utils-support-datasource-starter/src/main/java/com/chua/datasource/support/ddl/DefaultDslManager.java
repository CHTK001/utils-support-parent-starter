package com.chua.datasource.support.ddl;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.ddl.DialectAware;
import com.chua.common.support.lang.datasource.engine.ddl.DslManager;
import com.chua.common.support.lang.datasource.table.ColumnDef;
import com.chua.common.support.lang.datasource.table.TableDef;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.user.DataSourceAware;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用 JDBC DDL 管理器，作为 {@link DslManager} SPI 的兜底实现。
 *
 * <p>通过 JDBC {@link DatabaseMetaData} 读取真实表结构，再交由框架注入的
 * {@link Dialect} 生成数据库感知的 DDL 语句，自身不硬编码任何厂商语法。
 * 以别名 {@link DslManager#DEFAULT_ALIAS} 注册，供未提供方言专属实现的引擎回退使用。</p>
 *
 * <p>参数由 {@code AbstractEngine#ddl()} 自动注入，本类不要求调用方手工
 * {@code setDataSource} / {@code setDialect}；依赖这两个参数，
 * 缺失时由 {@link #requireContext()} 抛出可定位异常。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = DslManager.SPI_NAME, order = -100)
public class DefaultDslManager implements DslManager, DataSourceAware, DialectAware {

    /**
     * JDBC 数据源，由框架注入。
     */
    private DataSource dataSource;

    /**
     * 数据库方言，由框架注入。
     */
    private Dialect dialect;

    @Override
    /**
     * 设置数据源
     *
     * @param dataSource JDBC 数据源
     */
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    /**
     * 设置方言
     *
     * @param dialect 数据库方言
     */
    public void setDialect(Dialect dialect) {
        this.dialect = dialect;
    }

    /**
     * 获取 方言 实例。
     *
     * @return 数据库方言，未注入时返回 空
     */
    public Dialect getDialect() {
        return dialect;
    }

    @Override
    /**
     * 校验必需参数
     *
     * <p>在使用时校验而非解析时：解析阶段只做注入，
     * 避免"参数缺失"被误判为"引擎不支持 DDL"。</p>
     *
     * @throws IllegalStateException 数据源或方言未注入时抛出
     */
    public void requireContext() {
        if (dataSource == null) {
            throw new IllegalStateException("DDL 管理器缺少 DataSource：请确认默认数据源已注册且为 JDBC 类型");
        }
        if (dialect == null) {
            throw new IllegalStateException("DDL 管理器缺少 Dialect：请确认默认数据源已设置方言");
        }
    }

    @Override
    /**
     * 获取Table
     *
     * @param catalogName catalog 名称
     * @param schemaName 模式 名称
     * @param tableName 表名
     * @return 表定义，不存在返回 空
     */
    public TableDef getTable(String catalogName, String schemaName, String tableName) {
        requireContext();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String actualSchema = resolveActualSchema(meta, catalogName, schemaName, tableName);
            List<ColumnDef> columns = readColumns(meta, catalogName, actualSchema, tableName);
            if (columns.isEmpty()) {
                return null;
            }
            TableDef def = new TableDef();
            def.setName(tableName);
            def.setCatalog(catalogName);
            def.setSchema(actualSchema);

            List<String> primaryKeys = new ArrayList<>();
            try (ResultSet rs = meta.getPrimaryKeys(catalogName, actualSchema, tableName)) {
                while (rs.next()) {
                    primaryKeys.add(rs.getString("COLUMN_NAME"));
                }
            }
            for (ColumnDef col : columns) {
                if (primaryKeys.contains(col.getName())) {
                    col.setPrimaryKey(true);
                }
            }
            def.setPrimaryKeys(primaryKeys.toArray(new String[0]));
            def.setColumns(columns);
            return def;
        } catch (Exception e) {
            throw new IllegalStateException("读取表定义失败: " + tableName, e);
        }
    }

    @Override
    /**
     * 列出tables
     *
     * @param catalogName catalog 名称
     * @param schemaName 模式 名称
     * @return 表定义列表
     */
    public List<TableDef> listTables(String catalogName, String schemaName) {
        requireContext();
        List<TableDef> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            List<String> names = new ArrayList<>();
            try (ResultSet rs = meta.getTables(catalogName, schemaName, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    names.add(rs.getString("TABLE_NAME"));
                }
            }
            for (String tableName : names) {
                TableDef def = getTable(catalogName, schemaName, tableName);
                if (def != null) {
                    result.add(def);
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("列出表定义失败", e);
        }
    }

    @Override
    /**
     * 创建tableddl
     *
     * @param catalogName catalog 名称
     * @param schemaName 模式 名称
     * @param tableName 表名
     * @return 建表 语句
     */
    public String createTableDDL(String catalogName, String schemaName, String tableName) {
        TableDef def = getTable(catalogName, schemaName, tableName);
        if (def == null) {
            return "";
        }
        Dialect d = dialect;
        String table = d.quote(def.getName());
        String schemaPrefix = def.getSchema() == null || def.getSchema().isEmpty()
                ? "" : d.quote(def.getSchema()) + ".";
        StringBuilder sb = new StringBuilder(d.getCreateTableString());
        sb.append(' ').append(schemaPrefix).append(table).append(" (\n");
        // 方言不支持内联注释时（PG 系），注释需作为独立语句跟在建表之后
        StringBuilder afterDdl = new StringBuilder();

        List<ColumnDef> columns = def.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            ColumnDef c = columns.get(i);
            String colName = d.quote(c.getName());
            sb.append("  ").append(colName).append(' ').append(c.getType());
            if (c.getLength() != null && c.getLength() > 0) {
                sb.append('(').append(c.getLength()).append(')');
            }
            if (c.isAutoIncrement()) {
                sb.append(' ').append(d.getAutoIncrementKeyword());
            }
            if (!c.isNullable()) {
                sb.append(" NOT NULL");
            }
            if (c.getDefaultValue() != null) {
                sb.append(" DEFAULT ").append(c.getDefaultValue());
            }
            if (c.isPrimaryKey()) {
                sb.append(" PRIMARY KEY");
            }
            appendColumnComment(sb, afterDdl, d, schemaPrefix, table, colName, c.getComment());
            if (i < columns.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(')');
        appendTableComment(sb, afterDdl, d, schemaPrefix, table, def.getComment());
        if (afterDdl.length() > 0) {
            sb.append(";\n").append(afterDdl);
        }
        return sb.toString();
    }

    @Override
    /**
     * 重命名Table
     *
     * @param schemaName 模式 名称
     * @param oldTableName 原表名
     * @param newTableName 新表名
     * @return 重命名 语句
     */
    public String renameTable(String schemaName, String oldTableName, String newTableName) {
        requireContext();
        String schema = schemaName == null || schemaName.isEmpty() ? null : schemaName;
        return dialect.getRenameTableString(schema, oldTableName, newTableName);
    }

    @Override
    /**
     * 复制table结构
     *
     * @param schemaName 模式 名称
     * @param sourceTableName 源表名
     * @param targetTableName 目标表名
     * @return 复制结构 语句
     */
    public String copyTableStructure(String schemaName, String sourceTableName, String targetTableName) {
        requireContext();
        Dialect d = dialect;
        return "CREATE TABLE " + d.quote(targetTableName)
                + " AS SELECT * FROM " + d.quote(sourceTableName) + " WHERE 1=0";
    }

    @Override
    /**
     * 类型
     *
     * @return 类型名称
     */
    public String type() {
        return DslManager.DEFAULT_ALIAS;
    }

    /**
     * 读取列定义。
     *
     * @param meta JDBC 元数据
     * @param catalog catalog 名称
     * @param schema 模式 名称
     * @param table 表名
     * @return 列定义列表
     * @throws Exception 元数据读取失败时抛出
     */
    private List<ColumnDef> readColumns(DatabaseMetaData meta, String catalog, String schema, String table)
            throws Exception {
        List<ColumnDef> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                ColumnDef col = new ColumnDef();
                col.setName(rs.getString("COLUMN_NAME"));
                col.setType(rs.getString("TYPE_NAME"));
                col.setLength(rs.getLong("COLUMN_SIZE"));
                col.setNullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                col.setDefaultValue(rs.getString("COLUMN_DEF"));
                col.setComment(rs.getString("REMARKS"));
                col.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                columns.add(col);
            }
        }
        return columns;
    }

    /**
     * 解析 表 实际 所在 模式：不同厂商的 元数据 目录 约定不一致，
     * 未显式指定模式时以 元数据 实际返回值为准。
     *
     * @param meta JDBC 元数据
     * @param catalog catalog 名称
     * @param schema 模式 名称
     * @param table 表名
     * @return 实际 模式 名称，未找到返回 空
     * @throws Exception 元数据读取失败时抛出
     */
    private String resolveActualSchema(DatabaseMetaData meta, String catalog, String schema, String table)
            throws Exception {
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

    /**
     * 追加 列 注释。
     *
     * @param sb 建表 语句 缓冲
     * @param afterDdl 独立语句 缓冲
     * @param d 方言
     * @param schemaPrefix 模式 前缀（含点）
     * @param table 表名（已引用）
     * @param colName 列名（已引用）
     * @param comment 注释
     */
    private void appendColumnComment(StringBuilder sb, StringBuilder afterDdl, Dialect d,
                                     String schemaPrefix, String table, String colName, String comment) {
        if (comment == null || comment.isEmpty()) {
            return;
        }
        if (d.supportsInlineComment()) {
            String inline = d.getColumnComment(comment);
            if (inline != null && !inline.isEmpty()) {
                sb.append(' ').append(inline);
            }
            return;
        }
        afterDdl.append("COMMENT ON COLUMN ").append(schemaPrefix).append(table)
                .append('.').append(colName)
                .append(" IS '").append(escapeSqlString(comment)).append("';\n");
    }

    /**
     * 追加 表 注释。
     *
     * @param sb 建表 语句 缓冲
     * @param afterDdl 独立语句 缓冲
     * @param d 方言
     * @param schemaPrefix 模式 前缀（含点）
     * @param table 表名（已引用）
     * @param comment 注释
     */
    private void appendTableComment(StringBuilder sb, StringBuilder afterDdl, Dialect d,
                                    String schemaPrefix, String table, String comment) {
        if (comment == null || comment.isEmpty()) {
            return;
        }
        if (d.supportsInlineComment()) {
            String inline = d.getTableComment(comment);
            if (inline != null && !inline.isEmpty()) {
                sb.append(' ').append(inline);
            }
            return;
        }
        afterDdl.append("COMMENT ON TABLE ").append(schemaPrefix).append(table)
                .append(" IS '").append(escapeSqlString(comment)).append("';\n");
    }

    /**
     * 转义 单引号 字符串。
     *
     * @param value 原始字符串
     * @return 转义 后 字符串
     */
    private String escapeSqlString(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
