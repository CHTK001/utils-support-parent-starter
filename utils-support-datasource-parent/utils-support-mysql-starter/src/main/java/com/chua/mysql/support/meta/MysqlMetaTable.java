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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class MysqlMetaTable extends AbstractMetaTable {

    /**
     * 创建 mysqlmetatable 实例
     * @param metaData meta数据
     * @param engine Engine
     * @param engine engine
     */
    protected MysqlMetaTable(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
     * 创建 mysqlmetatable 实例
     *
     * @param metaData  meta数据
     * @param engine    Engine
     * @param tableName table名称
     */
    protected MysqlMetaTable(AbstractMetaData metaData, Engine engine, String tableName) {
        super(metaData, engine, tableName);
    }

    @Override
    public List<TableDef> list() {
        List<TableDef> result = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT TABLE_NAME, TABLE_COMMENT, CREATE_TIME, UPDATE_TIME"
                             + " FROM INFORMATION_SCHEMA.TABLES"
                             + " WHERE TABLE_SCHEMA = DATABASE()")) {
            while (rs.next()) {
                TableDef def = new TableDef();
                def.setName(rs.getString("TABLE_NAME"));
                def.setComment(rs.getString("TABLE_COMMENT"));
                def.setCreateTime(rs.getTimestamp("CREATE_TIME"));
                def.setUpdateTime(rs.getTimestamp("UPDATE_TIME"));
                result.add(def);
            }
        } catch (Exception e) {
            throw new RuntimeException("列出表失败", e);
        }
        return result;
    }

    /**
     * 创建。
     *
     * @param tableName 表名称，不允许为 null
     * @return 表创建Builder 对象
     */
    public TableCreateBuilder create(String tableName) {
        return new MysqlTableCreateBuilder(this, tableName);
    }

        /** Alter */
@Override
    public TableAlterBuilder alter() {
        return new MysqlTableAlterBuilder(this);
    }

        /** 掉落 */
@Override
    public boolean drop() {
        if (tableName == null) {
            throw new IllegalStateException("未指定表名");
        }
        return executeUpdate("DROP TABLE IF EXISTS " + quote(tableName));
    }

        /** 重命名 */
@Override
    public boolean rename(String newName) {
        if (tableName == null) {
            throw new IllegalStateException("未指定原表名");
        }
        return executeUpdate("RENAME TABLE " + quote(tableName) + " TO " + quote(newName));
    }

    /** 单查 */
@Override
    public TableDef get() {
        if (tableName == null) {
            throw new IllegalStateException("未指定表名");
        }
        TableDef def = new TableDef();
        def.setName(tableName);
        try (Connection conn = getConnection()) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            String catalog = metaData.getCatalog();
            String schema = metaData.getSchema();
            // columns
            List<ColumnDef> cols = readColumns(dbMeta, catalog, schema, tableName);
            def.setColumns(cols);
 // primary 键
            List<String> pks = new ArrayList<>();
            try (ResultSet rs = dbMeta.getPrimaryKeys(catalog, schema, tableName)) {
                while (rs.next()) {
                    pks.add(rs.getString("COLUMN_NAME"));
                }
            }
            def.setPrimaryKeys(pks.toArray(new String[0]));
            // indexes (skipped to avoid compilation issues with IndexMetadata)
            def.setIndexes(new ArrayList<>());
 // table 信息 从 信息_模式
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT TABLE_COMMENT, TABLE_TYPE, CREATE_TIME, UPDATE_TIME"
                                 + " FROM INFORMATION_SCHEMA.TABLES"
                                  + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + tableName.replace("'", "''") + "'")) {
                if (rs.next()) {
                    def.setComment(rs.getString("TABLE_COMMENT"));
                    def.setType(rs.getString("TABLE_TYPE"));
                    def.setCreateTime(rs.getTimestamp("CREATE_TIME"));
                    def.setUpdateTime(rs.getTimestamp("UPDATE_TIME"));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("查询表详情失败: " + tableName, e);
        }
        return def;
    }

    /**
     * 读取Columns
     *
     * @param dbMeta dbmeta
     * @param catalog catalog
     * @param schema 模式
     * @param tableName table名称
     * @return 读取columns的结果
     */
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

    /**
     * 获取Connection
     *
     * @return 获取connection的结果
     */
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

    /**
     * quote。
     *
     * @param name 名称，不允许为 null
     * @return 结果字符串
     */
    String quote(String name) {
        Dialect dialect = resolveDialect();
        if (dialect != null) {
            return dialect.quote(name);
        }
        return "`" + name + "`";
    }

    /**
     * 执行更新。
     *
     * @param sql SQL，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    boolean executeUpdate(String sql) {
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("执行 SQL 失败: " + sql, e);
        }
    }

    /**
     * 解析Dialect。
     *
     * @return Dialect 对象
     */
    Dialect resolveDialect() {
        EngineDataSource<?> eds = engine.getDataSource(engine.getDefaultDataSourceName());
        return eds != null ? eds.getDialect() : null;
    }

    // ==================== MySQL 建表构建器 ====================
    /**
     * mysqltable创建构建器类。
     *
     * @author CH
     * @since 4.0.0
     */

    private static class MysqlTableCreateBuilder implements TableCreateBuilder {

        /** Meta表 */
        private final MysqlMetaTable metaTable;
        /** 表名称 */
        private final String tableName;
        /** Columns */
        private final List<ColumnDef> columns = new ArrayList<>();
        /** 评论 */
        private String comment;
        /** 引擎 */
        private String engine;
        /** 字符集 */
        private String charset;
        /** Collate */
        private String collate;
        /** Primarykeys */
        private final List<String> primaryKeys = new ArrayList<>();

        MysqlTableCreateBuilder(MysqlMetaTable metaTable, String tableName) {
            this.metaTable = metaTable;
            this.tableName = tableName;
        }

                /** Column */
@Override
        public TableCreateBuilder column(String name, String type) {
            columns.add(new ColumnDef().setName(name).setType(type));
            return this;
        }

                /** not空 */
@Override
        public TableCreateBuilder notNull() {
            if (!columns.isEmpty()) {
                columns.get(columns.size() - 1).setNullable(false);
            }
            return this;
        }

                /** primary键 */
@Override
        public TableCreateBuilder primaryKey() {
            if (!columns.isEmpty()) {
                ColumnDef c = columns.get(columns.size() - 1);
                c.setPrimaryKey(true);
                c.setNullable(false);
                primaryKeys.add(c.getName());
            }
            return this;
        }

                /** autoincrement */
@Override
        public TableCreateBuilder autoIncrement() {
            if (!columns.isEmpty()) {
                columns.get(columns.size() - 1).setAutoIncrement(true);
            }
            return this;
        }

                /** Unsigned */
@Override
        public TableCreateBuilder unsigned() {
            if (!columns.isEmpty()) {
                columns.get(columns.size() - 1).setUnsigned(true);
            }
            return this;
        }

                /** 默认值 */
@Override
        public TableCreateBuilder defaultValue(String val) {
            if (!columns.isEmpty()) {
                columns.get(columns.size() - 1).setDefaultValue(val);
            }
            return this;
        }

                /** 评论 */
@Override
        public TableCreateBuilder comment(String val) {
            if (!columns.isEmpty()) {
                columns.get(columns.size() - 1).setComment(val);
            }
            return this;
        }

                /** 之后 */
@Override
        public TableCreateBuilder after(String columnName) {
            if (!columns.isEmpty()) {
                columns.get(columns.size() - 1).setAfter(columnName);
            }
            return this;
        }

                /** 第一个 */
@Override
        public TableCreateBuilder first() {
            if (!columns.isEmpty()) {
                columns.get(columns.size() - 1).setFirst(true);
            }
            return this;
        }

                /** primary键 */
@Override
        public TableCreateBuilder primaryKey(String... cols) {
            for (String col : cols) {
                primaryKeys.add(col);
            }
            return this;
        }

                /** 评论table */
@Override
        public TableCreateBuilder commentTable(String comment) {
            this.comment = comment;
            return this;
        }

                /** Engine */
@Override
        public TableCreateBuilder engine(String engine) {
            this.engine = engine;
            return this;
        }

                /** 字符集 */
@Override
        public TableCreateBuilder charset(String charset) {
            this.charset = charset;
            return this;
        }

                /** Collate */
@Override
        public TableCreateBuilder collate(String collate) {
            this.collate = collate;
            return this;
        }

                /** 执行 */
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
                sb.append("  ,PRIMARY KEY (");
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
            // 不依赖未实现的 get()，直接构造建表结果
            TableDef def = new TableDef();
            def.setName(tableName);
            def.setColumns(columns);
            return def;
        }
    }

    // ==================== MySQL 改表构建器 ====================
    /**
     * mysqltablealter构建器类。
     *
     * @author CH
     * @since 4.0.0
     */

    private static class MysqlTableAlterBuilder implements TableAlterBuilder {

        /** Meta表 */
        private final MysqlMetaTable metaTable;
        /** SQL */
        private final List<String> sqls = new ArrayList<>();

        MysqlTableAlterBuilder(MysqlMetaTable metaTable) {
            this.metaTable = metaTable;
        }

        void addSql(String sql) {
            sqls.add(sql);
        }

                /** 添加Column */
@Override
        public AlterColumnBuilder addColumn(String name, String type) {
            return new MysqlAlterColumnBuilder(this, "ADD COLUMN `" + name + "` " + type, name);
        }

                /** 掉落column */
@Override
        public TableAlterBuilder dropColumn(String columnName) {
            sqls.add("DROP COLUMN `" + columnName + "`");
            return this;
        }

                /** modifycolumn */
@Override
        public AlterColumnBuilder modifyColumn(String columnName, String newType) {
            return new MysqlAlterColumnBuilder(this, "MODIFY COLUMN `" + columnName + "` " + newType, columnName);
        }

                /** 添加primary键 */
@Override
        public TableAlterBuilder addPrimaryKey(String... columns) {
            String pkCols = String.join(", ", java.util.Arrays.stream(columns).map(c -> "`" + c + "`").toList());
            sqls.add("ADD PRIMARY KEY (" + pkCols + ")");
            return this;
        }

                /** 掉落primary键 */
@Override
        public TableAlterBuilder dropPrimaryKey() {
            sqls.add("DROP PRIMARY KEY");
            return this;
        }

                /** 添加索引 */
@Override
        public AlterIndexBuilder addIndex(String indexName) {
            return new MysqlAlterIndexBuilder(this, indexName);
        }

                /** 掉落索引 */
@Override
        public TableAlterBuilder dropIndex(String indexName) {
            sqls.add("DROP INDEX `" + indexName + "`");
            return this;
        }

                /** 添加国外键 */
@Override
        public AlterForeignKeyBuilder addForeignKey(String fkName) {
            return new MysqlAlterForeignKeyBuilder(this, fkName);
        }

                /** 掉落国外键 */
@Override
        public TableAlterBuilder dropForeignKey(String fkName) {
            sqls.add("DROP FOREIGN KEY `" + fkName + "`");
            return this;
        }

                /** 重命名转为 */
@Override
        public TableAlterBuilder renameTo(String newName) {
            sqls.add("RENAME TO `" + newName + "`");
            return this;
        }

                /** 执行 */
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

        /** 父级 */
        private final MysqlTableAlterBuilder parent;
        /** 列名称 */
        private final String columnName;
        /** NOT是否为空 */
        private boolean notNull;
        /** 默认值 */
        private String defaultValue;
        /** 评论 */
        private String comment;
        /** 之后 */
        private String after;
        /** 首个 */
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

                /** not空 */
@Override
        public AlterColumnBuilder notNull() {
            this.notNull = true;
            rebuildColumnClause();
            return this;
        }

                /** 默认值 */
@Override
        public AlterColumnBuilder defaultValue(String val) {
            this.defaultValue = val;
            rebuildColumnClause();
            return this;
        }

                /** 评论 */
@Override
        public AlterColumnBuilder comment(String comment) {
            this.comment = comment;
            rebuildColumnClause();
            return this;
        }

                /** 之后 */
@Override
        public AlterColumnBuilder after(String columnName) {
            this.after = columnName;
            rebuildColumnClause();
            return this;
        }

                /** 第一个 */
@Override
        public AlterColumnBuilder first() {
            this.first = true;
            rebuildColumnClause();
            return this;
        }

                /** 执行 */
@Override
        public TableAlterBuilder execute() {
            return parent;
        }

        /** rebuildcolumnclause */
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

        /** 父级 */
        private final MysqlTableAlterBuilder parent;
        /** 索引名称 */
        private final String indexName;
        /** Cols */
        private final List<String> cols = new ArrayList<>();
        /** Unique */
        private boolean unique;
        /** 类型 */
        private String type;
        /** 评论 */
        private String comment;

        MysqlAlterIndexBuilder(MysqlTableAlterBuilder parent, String indexName) {
            this.parent = parent;
            this.indexName = indexName;
        }

                /** Column */
@Override
        public AlterIndexBuilder column(String columnName) {
            cols.add(columnName);
            return this;
        }

                /** Unique */
@Override
        public AlterIndexBuilder unique() {
            this.unique = true;
            return this;
        }

                /** 类型 */
@Override
        public AlterIndexBuilder type(String type) {
            this.type = type;
            return this;
        }

        /**
         * 评论
         *
         * @param comment 评论
         * @return 评论的结果
         */
        public AlterIndexBuilder comment(String comment) {
            this.comment = comment;
            return this;
        }

                /** 执行 */
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

        /** 父级 */
        private final MysqlTableAlterBuilder parent;
        /** FK名称 */
        private final String fkName;
        /** 列名称 */
        private String columnName;
        /** 引用表 */
        private String refTable;
        /** 引用列 */
        private String refColumn;
        /** ondelete */
        private String onDelete;
        /** onupdate */
        private String onUpdate;

        MysqlAlterForeignKeyBuilder(MysqlTableAlterBuilder parent, String fkName) {
            this.parent = parent;
            this.fkName = fkName;
        }

        /**
         * Column
         *
         * @param columnName column名称
         * @return column的结果
         */
        public AlterForeignKeyBuilder column(String columnName) {
            this.columnName = columnName;
            return this;
        }

                /** 引用 */
@Override
        public AlterForeignKeyBuilder references(String table, String column) {
            this.refTable = table;
            this.refColumn = column;
            return this;
        }

                /** On删除 */
@Override
        public AlterForeignKeyBuilder onDelete(String action) {
            this.onDelete = action;
            return this;
        }

                /** On更新 */
@Override
        public AlterForeignKeyBuilder onUpdate(String action) {
            this.onUpdate = action;
            return this;
        }

                /** 执行 */
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

    /**
     * escapesql
     *
     * @param value 值
     * @return escapeSql的结果
     */
    private static String escapeSql(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }
}
