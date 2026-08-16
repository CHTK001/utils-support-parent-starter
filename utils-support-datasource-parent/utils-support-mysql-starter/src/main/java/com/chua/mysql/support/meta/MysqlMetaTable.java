package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.meta.AlterColumnBuilder;
import com.chua.common.support.lang.datasource.meta.AlterIndexBuilder;
import com.chua.common.support.lang.datasource.meta.AlterForeignKeyBuilder;
import com.chua.common.support.lang.datasource.meta.TableAlterBuilder;
import com.chua.common.support.lang.datasource.meta.TableCreateBuilder;
import com.chua.common.support.lang.datasource.table.ColumnDef;
import com.chua.common.support.lang.datasource.table.TableDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaTable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class MysqlMetaTable extends AbstractMetaTable {

    protected MysqlMetaTable(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    protected MysqlMetaTable(AbstractMetaData metaData, Engine engine, String tableName) {
        super(metaData, engine, tableName);
    }

    @Override
    public TableCreateBuilder create(String tableName) {
        return new MysqlTableCreateBuilder(this, tableName);
    }

    @Override
    public TableAlterBuilder alter() {
        return new MysqlTableAlterBuilder(this);
    }

    @Override
    public boolean drop() {
        if (tableName == null) {
            throw new IllegalStateException("未指定表名");
        }
        return executeUpdate("DROP TABLE IF EXISTS " + quote(tableName));
    }

    @Override
    public boolean rename(String newName) {
        if (tableName == null) {
            throw new IllegalStateException("未指定原表名");
        }
        return executeUpdate("RENAME TABLE " + quote(tableName) + " TO " + quote(newName));
    }

    protected List<ColumnDef> readColumns(DatabaseMetaData dbMeta, String catalog, String schema, String tableName) throws SQLException {
        List<ColumnDef> columns = new ArrayList<>();
        try (ResultSet rs = dbMeta.getColumns(catalog, schema, tableName, "%")) {
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

    protected Connection getConnection() throws Exception {
        EngineDataSource<?> eds = engine.getDataSource(engine.getDefaultDataSourceName());
        if (eds == null) {
            throw new IllegalStateException("默认数据源未配置");
        }
        Object source = eds.getSource();
        if (source instanceof DataSource ds) {
            return ds.getConnection();
        }
        throw new IllegalStateException("数据源类型不支持 JDBC 连接获取: " + source.getClass().getName());
    }

    String quote(String name) {
        Dialect dialect = resolveDialect();
        if (dialect != null) {
            return dialect.quote(name);
        }
        return "`" + name + "`";
    }

    boolean executeUpdate(String sql) {
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("执行 SQL 失败: " + sql, e);
        }
    }

    Dialect resolveDialect() {
        EngineDataSource<?> eds = engine.getDataSource(engine.getDefaultDataSourceName());
        return eds != null ? eds.getDialect() : null;
    }

    // ==================== MySQL 建表构建器 ====================

    private static class MysqlTableCreateBuilder implements TableCreateBuilder {

        private final MysqlMetaTable metaTable;
        private final String tableName;
        private final List<ColumnDef> columns = new ArrayList<>();
        private String comment;
        private String engine;
        private String charset;
        private String collate;
        private final List<String> primaryKeys = new ArrayList<>();

        MysqlTableCreateBuilder(MysqlMetaTable metaTable, String tableName) {
            this.metaTable = metaTable;
            this.tableName = tableName;
        }

        @Override
        public TableCreateBuilder column(String name, String type) {
            columns.add(new ColumnDef().setName(name).setType(type));
            return this;
        }

        @Override
        public TableCreateBuilder notNull() {
            if (!columns.isEmpty()) {
                columns.get(columns.size() - 1).setNullable(false);
            }
            return this;
        }

        @Override
        public TableCreateBuilder primaryKey() {
            if (!columns.isEmpty()) {
                ColumnDef c = columns.get(columns.size() - 1);
                c.setPrimaryKey(true);
                c.setNullable(false);
            }
            return this;
        }

        @Override
        public TableCreateBuilder autoIncrement() {
            if (!columns.isEmpty()) {
                columns.get(columns.size() - 1).setAutoIncrement(true);
            }
            return this;
        }

        @Override
        public TableCreateBuilder unsigned() {
            if (!columns.isEmpty()) {
                columns.get(columns.size() - 1).setUnsigned(true);
            }
            return this;
        }

        @Override
        public TableCreateBuilder defaultValue(String val) {
            if (!columns.isEmpty()) {
                columns.get(columns.size() - 1).setDefaultValue(val);
            }
            return this;
        }

        @Override
        public TableCreateBuilder comment(String val) {
            if (!columns.isEmpty()) {
                columns.get(columns.size() - 1).setComment(val);
            }
            return this;
        }

        @Override
        public TableCreateBuilder after(String columnName) {
            if (!columns.isEmpty()) {
                columns.get(columns.size() - 1).setAfter(columnName);
            }
            return this;
        }

        @Override
        public TableCreateBuilder first() {
            if (!columns.isEmpty()) {
                columns.get(columns.size() - 1).setFirst(true);
            }
            return this;
        }

        @Override
        public TableCreateBuilder primaryKey(String... cols) {
            for (String col : cols) {
                primaryKeys.add(col);
            }
            return this;
        }

        @Override
        public TableCreateBuilder commentTable(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        public TableCreateBuilder engine(String engine) {
            this.engine = engine;
            return this;
        }

        @Override
        public TableCreateBuilder charset(String charset) {
            this.charset = charset;
            return this;
        }

        @Override
        public TableCreateBuilder collate(String collate) {
            this.collate = collate;
            return this;
        }

        @Override
        public TableDef execute() {
            Dialect dialect = metaTable.resolveDialect();
            StringBuilder sb = new StringBuilder();
            sb.append("CREATE TABLE ").append(metaTable.quote(tableName)).append(" (\n");
            for (int i = 0; i < columns.size(); i++) {
                ColumnDef col = columns.get(i);
                sb.append("  ").append(metaTable.quote(col.getName())).append(" ").append(col.getType());
                if (col.isAutoIncrement()) {
                    sb.append(" ").append(dialect != null ? dialect.getAutoIncrementKeyword() : "AUTO_INCREMENT");
                }
                if (!col.isNullable()) {
                    sb.append(" NOT NULL");
                }
                if (col.getDefaultValue() != null && !col.getDefaultValue().isEmpty()) {
                    sb.append(" DEFAULT ").append(col.getDefaultValue());
                }
                if (col.getComment() != null && !col.getComment().isEmpty()) {
                    sb.append(" COMMENT '").append(escapeSql(col.getComment())).append("'");
                }
                if (i < columns.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            if (!primaryKeys.isEmpty()) {
                sb.append("  PRIMARY KEY (");
                sb.append(String.join(", ", primaryKeys.stream().map(metaTable::quote).toList()));
                sb.append(")\n");
            }
            sb.append(")");
            if (engine != null) {
                sb.append(" ENGINE=").append(engine);
            }
            if (charset != null) {
                sb.append(" DEFAULT CHARSET=").append(charset);
            }
            if (collate != null) {
                sb.append(" COLLATE=").append(collate);
            }
            if (comment != null) {
                sb.append(" COMMENT='").append(escapeSql(comment)).append("'");
            }
            sb.append(";");
            String sql = sb.toString();
            metaTable.executeUpdate(sql);
            TableDef def = metaTable.get();
            if (def == null) {
                def = new TableDef();
                def.setName(tableName);
                def.setColumns(columns);
            }
            return def;
        }
    }

    // ==================== MySQL 改表构建器 ====================

    private static class MysqlTableAlterBuilder implements TableAlterBuilder {

        private final MysqlMetaTable metaTable;
        private final List<String> sqls = new ArrayList<>();

        MysqlTableAlterBuilder(MysqlMetaTable metaTable) {
            this.metaTable = metaTable;
        }

        void addSql(String sql) {
            sqls.add(sql);
        }

        @Override
        public AlterColumnBuilder addColumn(String name, String type) {
            return new MysqlAlterColumnBuilder(this, "ADD COLUMN `" + name + "` " + type, name);
        }

        @Override
        public TableAlterBuilder dropColumn(String columnName) {
            sqls.add("DROP COLUMN `" + columnName + "`");
            return this;
        }

        @Override
        public AlterColumnBuilder modifyColumn(String columnName, String newType) {
            return new MysqlAlterColumnBuilder(this, "MODIFY COLUMN `" + columnName + "` " + newType, columnName);
        }

        @Override
        public TableAlterBuilder addPrimaryKey(String... columns) {
            String pkCols = String.join(", ", java.util.Arrays.stream(columns).map(c -> "`" + c + "`").toList());
            sqls.add("ADD PRIMARY KEY (" + pkCols + ")");
            return this;
        }

        @Override
        public TableAlterBuilder dropPrimaryKey() {
            sqls.add("DROP PRIMARY KEY");
            return this;
        }

        @Override
        public AlterIndexBuilder addIndex(String indexName) {
            return new MysqlAlterIndexBuilder(this, indexName);
        }

        @Override
        public TableAlterBuilder dropIndex(String indexName) {
            sqls.add("DROP INDEX `" + indexName + "`");
            return this;
        }

        @Override
        public AlterForeignKeyBuilder addForeignKey(String fkName) {
            return new MysqlAlterForeignKeyBuilder(this, fkName);
        }

        @Override
        public TableAlterBuilder dropForeignKey(String fkName) {
            sqls.add("DROP FOREIGN KEY `" + fkName + "`");
            return this;
        }

        @Override
        public TableAlterBuilder renameTo(String newName) {
            sqls.add("RENAME TO `" + newName + "`");
            return this;
        }

        @Override
        public TableDef execute() {
            if (sqls.isEmpty()) {
                throw new IllegalStateException("没有需要执行的变更");
            }
            String tableName = metaTable.tableName != null ? metaTable.tableName : "";
            if (tableName.isEmpty()) {
                throw new IllegalStateException("未指定表名");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("ALTER TABLE ").append(metaTable.quote(tableName)).append("\n");
            for (int i = 0; i < sqls.size(); i++) {
                sb.append("  ").append(sqls.get(i));
                if (i < sqls.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            metaTable.executeUpdate(sb.toString());
            return metaTable.get();
        }
    }

    private static class MysqlAlterColumnBuilder implements AlterColumnBuilder {

        private final MysqlTableAlterBuilder parent;
        private final String columnName;
        private boolean notNull;
        private String defaultValue;
        private String comment;
        private String after;
        private boolean first;

        MysqlAlterColumnBuilder(MysqlTableAlterBuilder parent, String clause, String columnName) {
            this.parent = parent;
            this.columnName = columnName;
            StringBuilder sql = new StringBuilder();
            sql.append(clause);
            if (clause.contains("ADD COLUMN")) {
                sql.append(" NOT NULL");
            }
            parent.addSql(sql.toString());
        }

        @Override
        public AlterColumnBuilder notNull() {
            this.notNull = true;
            rebuildColumnClause();
            return this;
        }

        @Override
        public AlterColumnBuilder defaultValue(String val) {
            this.defaultValue = val;
            rebuildColumnClause();
            return this;
        }

        @Override
        public AlterColumnBuilder comment(String comment) {
            this.comment = comment;
            rebuildColumnClause();
            return this;
        }

        @Override
        public AlterColumnBuilder after(String columnName) {
            this.after = columnName;
            rebuildColumnClause();
            return this;
        }

        @Override
        public AlterColumnBuilder first() {
            this.first = true;
            rebuildColumnClause();
            return this;
        }

        @Override
        public TableAlterBuilder execute() {
            return parent;
        }

        private void rebuildColumnClause() {
            String base = parent.sqls.getLast();
            StringBuilder sb = new StringBuilder();
            sb.append(base.substring(0, base.indexOf("`") + 1 + columnName.length() + 1));
            sb.append(columnName).append("` ");
            if (notNull) {
                sb.append("NOT NULL ");
            }
            if (defaultValue != null && !defaultValue.isEmpty()) {
                sb.append("DEFAULT ").append(defaultValue).append(" ");
            }
            if (first) {
                sb.append("FIRST ");
            } else if (after != null && !after.isEmpty()) {
                sb.append("AFTER `").append(after).append("` ");
            }
            if (comment != null && !comment.isEmpty()) {
                sb.append("COMMENT '").append(escapeSql(comment)).append("' ");
            }
            parent.sqls.set(parent.sqls.size() - 1, sb.toString().trim());
        }
    }

    private static class MysqlAlterIndexBuilder implements AlterIndexBuilder {

        private final MysqlTableAlterBuilder parent;
        private final String indexName;
        private final List<String> cols = new ArrayList<>();
        private boolean unique;
        private String type;
        private String comment;

        MysqlAlterIndexBuilder(MysqlTableAlterBuilder parent, String indexName) {
            this.parent = parent;
            this.indexName = indexName;
        }

        @Override
        public AlterIndexBuilder column(String columnName) {
            cols.add(columnName);
            return this;
        }

        @Override
        public AlterIndexBuilder unique() {
            this.unique = true;
            return this;
        }

        @Override
        public AlterIndexBuilder type(String type) {
            this.type = type;
            return this;
        }

        public AlterIndexBuilder comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        public TableAlterBuilder execute() {
            StringBuilder sb = new StringBuilder();
            if (unique) {
                sb.append("ADD UNIQUE INDEX `").append(indexName).append("` (");
            } else if (type != null && !type.isEmpty()) {
                sb.append("ADD INDEX `").append(indexName).append("` USING ").append(type).append(" (");
            } else {
                sb.append("ADD INDEX `").append(indexName).append("` (");
            }
            sb.append(String.join(", ", cols.stream().map(c -> "`" + c + "`").toList()));
            sb.append(")");
            if (comment != null && !comment.isEmpty()) {
                sb.append(" COMMENT '").append(escapeSql(comment)).append("'");
            }
            parent.addSql(sb.toString());
            return parent;
        }
    }

    private static class MysqlAlterForeignKeyBuilder implements AlterForeignKeyBuilder {

        private final MysqlTableAlterBuilder parent;
        private final String fkName;
        private String columnName;
        private String refTable;
        private String refColumn;
        private String onDelete;
        private String onUpdate;

        MysqlAlterForeignKeyBuilder(MysqlTableAlterBuilder parent, String fkName) {
            this.parent = parent;
            this.fkName = fkName;
        }

        public AlterForeignKeyBuilder column(String columnName) {
            this.columnName = columnName;
            return this;
        }

        @Override
        public AlterForeignKeyBuilder references(String table, String column) {
            this.refTable = table;
            this.refColumn = column;
            return this;
        }

        @Override
        public AlterForeignKeyBuilder onDelete(String action) {
            this.onDelete = action;
            return this;
        }

        @Override
        public AlterForeignKeyBuilder onUpdate(String action) {
            this.onUpdate = action;
            return this;
        }

        @Override
        public TableAlterBuilder execute() {
            StringBuilder sb = new StringBuilder();
            sb.append("ADD CONSTRAINT `").append(fkName).append("` FOREIGN KEY (`").append(columnName).append("`) ");
            sb.append("REFERENCES `").append(refTable).append("` (`").append(refColumn).append("`)");
            if (onDelete != null && !onDelete.isEmpty()) {
                sb.append(" ON DELETE ").append(onDelete);
            }
            if (onUpdate != null && !onUpdate.isEmpty()) {
                sb.append(" ON UPDATE ").append(onUpdate);
            }
            parent.addSql(sb.toString());
            return parent;
        }
    }

    private static String escapeSql(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }
}
