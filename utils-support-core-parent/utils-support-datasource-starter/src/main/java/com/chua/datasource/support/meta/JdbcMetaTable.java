package com.chua.datasource.support.meta;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.meta.TableCreateBuilder;
import com.chua.common.support.lang.datasource.table.ColumnDef;
import com.chua.common.support.lang.datasource.table.TableDef;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用 JDBC 表元数据操作，基于方言生成 DDL。
 *
 * <p>适用于 SQLite、DuckDB 等标准 SQL 兼容数据库，提供对象化建表 DSL：
 * {@code engine.meta().table("user").create("user").column("id", "INTEGER").primaryKey()...execute()}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class JdbcMetaTable extends AbstractMetaTable {

    /**
     * 构造方法（无表名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    public JdbcMetaTable(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
     * 构造方法（带表名上下文）。
     *
     * @param metaData  元数据入口
     * @param engine    引擎实例
     * @param tableName 表名
     */
    public JdbcMetaTable(AbstractMetaData metaData, Engine engine, String tableName) {
        super(metaData, engine, tableName);
    }

    /**
     * 创建建表构建器。
     *
     * @param tableName 表名
     * @return 建表链式构建器
     */
    @Override
    public TableCreateBuilder create(String tableName) {
        return new JdbcTableCreateBuilder(this, tableName);
    }

    /**
     * 删除当前表。
     *
     * @return 是否成功
     */
    @Override
    public boolean drop() {
        if (tableName == null) {
            throw new IllegalStateException("未指定表名");
        }
        return executeUpdate("DROP TABLE IF EXISTS " + quote(tableName));
    }

    /**
     * 重命名当前表。
     *
     * @param newName 新表名
     * @return 是否成功
     */
    @Override
    public boolean rename(String newName) {
        if (tableName == null) {
            throw new IllegalStateException("未指定原表名");
        }
        return executeUpdate("ALTER TABLE " + quote(tableName) + " RENAME TO " + quote(newName));
    }

    /**
     * 执行更新语句。
     *
     * @param sql SQL 语句
     * @return 是否成功
     */
    protected boolean executeUpdate(String sql) {
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("执行 SQL 失败: " + sql, e);
        }
    }

    /**
     * 构建执行成功的表定义（供构建器回填）。
     *
     * @param tableName 表名
     * @param columns   列定义
     * @return 表定义
     */
    protected TableDef buildTableDef(String tableName, List<ColumnDef> columns) {
        TableDef def = new TableDef();
        def.setName(tableName);
        def.setColumns(columns);
        return def;
    }

    /**
     * 引用标识符（使用方言引用符）。
     *
     * @param name 标识符
     * @return 引用后的标识符
     */
    protected String quote(String name) {
        Dialect dialect = resolveDialect();
        if (dialect != null) {
            return dialect.quote(name);
        }
        return name;
    }

    /**
     * 解析方言。
     *
     * @return 方言实例
     */
    protected Dialect resolveDialect() {
        String name = engine.getDefaultDataSourceName();
        if (name == null) {
            return null;
        }
        EngineDataSource<?> eds = engine.getDataSource(name);
        return eds != null ? eds.getDialect() : null;
    }

    /**
     * 获取 JDBC 连接。
     *
     * @return 连接
     * @throws SQLException 获取失败
     */
    protected Connection getConnection() throws SQLException {
        EngineDataSource<?> eds = engine.getDataSource(engine.getDefaultDataSourceName());
        if (eds == null) {
            throw new IllegalStateException("默认数据源未配置");
        }
        Object source = eds.getSource();
        if (source instanceof DataSource dataSource) {
            return dataSource.getConnection();
        }
        throw new IllegalStateException("数据源类型不支持 JDBC 连接获取");
    }

    /**
     * 通用建表链式构建器，基于方言生成 CREATE TABLE。
     *
     * @author CH
     * @since 4.0.0.42
     */
    public static class JdbcTableCreateBuilder implements TableCreateBuilder {

        /**
         * 父表元数据
         */
        private final JdbcMetaTable metaTable;

        /**
         * 表名
         */
        private final String tableName;

        /**
         * 列定义
         */
        private final List<ColumnDef> columns = new ArrayList<>();

        /**
         * 联合主键
         */
        private final List<String> primaryKeys = new ArrayList<>();

        /**
         * 表注释
         */
        private String tableComment;

        /**
         * 构tablename 建表构建器。
         *
         * @param metaTable 父表元数据
         * @param tableName 表名
         */
        public JdbcTableCreateBuilder(JdbcMetaTable metaTable, String tableName) {
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
                ColumnDef col = columns.get(columns.size() - 1);
                col.setPrimaryKey(true);
                col.setNullable(false);
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
            this.tableComment = comment;
            return this;
        }

        @Override
        public TableCreateBuilder engine(String engine) {
            return this;
        }

        @Override
        public TableCreateBuilder charset(String charset) {
            return this;
        }

        @Override
        public TableCreateBuilder collate(String collate) {
            return this;
        }

        @Override
        public TableDef execute() {
            Dialect dialect = metaTable.resolveDialect();
            StringBuilder sql = new StringBuilder("CREATE TABLE ");
            sql.append(metaTable.quote(tableName)).append(" (\n");
            for (int i = 0; i < columns.size(); i++) {
                ColumnDef col = columns.get(i);
                sql.append("  ").append(metaTable.quote(col.getName())).append(" ").append(col.getType());
                if (col.isAutoIncrement()) {
                    sql.append(" ").append(dialect != null ? dialect.getAutoIncrementKeyword() : "AUTO_INCREMENT");
                }
                if (!col.isNullable()) {
                    sql.append(" NOT NULL");
                }
                if (col.isPrimaryKey()) {
                    sql.append(" PRIMARY KEY");
                }
                if (col.getDefaultValue() != null && !col.getDefaultValue().isEmpty()) {
                    sql.append(" DEFAULT ").append(col.getDefaultValue());
                }
                if (i < columns.size() - 1) {
                    sql.append(",");
                }
                sql.append("\n");
            }
            if (!primaryKeys.isEmpty()) {
                sql.append("  PRIMARY KEY (");
                sql.append(String.join(", ", primaryKeys.stream().map(metaTable::quote).toList()));
                sql.append(")\n");
            }
            sql.append(")");
            metaTable.executeUpdate(sql.toString());
            TableDef def = metaTable.buildTableDef(tableName, columns);
            if (tableComment != null) {
                def.setComment(tableComment);
            }
            return def;
        }
    }
}
