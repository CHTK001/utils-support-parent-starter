package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.dialect.meta.IndexMetadata;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.AlterColumnBuilder;
import com.chua.common.support.lang.datasource.meta.AlterForeignKeyBuilder;
import com.chua.common.support.lang.datasource.meta.AlterIndexBuilder;
import com.chua.common.support.lang.datasource.meta.TableAlterBuilder;
import com.chua.common.support.lang.datasource.meta.TableCreateBuilder;
import com.chua.common.support.lang.datasource.table.ColumnDef;
import com.chua.common.support.lang.datasource.table.TableDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaTable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MySQL 表元数据操作。
 * <p>
 * 读取路径全部落在 {@code INFORMATION_SCHEMA} 上，并带
 * {@code TABLE_SCHEMA = COALESCE(?, DATABASE())} 过滤：调用方通过
 * {@link AbstractMetaData#getSchema()} / {@link AbstractMetaData#getCatalog()} 指定库名时按参数绑定，
 * 未指定时只返回当前会话默认库，不会把跨库同名表混进来。
 * </p>
 * <p>
 * 能力边界：
 * <ul>
 *   <li>{@link #list()} 返回表级属性（引擎、字符集、排序规则、注释、行数、创建/更新时间），
 *       不含列与索引明细，避免对全库做 N+1 查询；列与索引明细由 {@link #get()} 提供。</li>
 *   <li>{@code TableDef.rowCount} 取自 {@code TABLES.TABLE_ROWS}，InnoDB 下为估算值。</li>
 *   <li>{@code TableDef.type} 直接透传厂商值：{@code BASE TABLE} / {@code VIEW} / {@code SYSTEM VIEW}。</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MysqlMetaTable extends AbstractMetaTable {

    /**
     * 表级属性查询：库名以绑定参数下发，参数为 {@code null} 时回落到 {@code DATABASE()}。
     */
    private static final String TABLE_SQL =
            "SELECT t.TABLE_CATALOG, t.TABLE_SCHEMA, t.TABLE_NAME, t.TABLE_TYPE, t.ENGINE,"
                    + " t.TABLE_COLLATION, t.TABLE_COMMENT, t.TABLE_ROWS, t.CREATE_TIME, t.UPDATE_TIME,"
                    + " cs.CHARACTER_SET_NAME"
                    + " FROM INFORMATION_SCHEMA.TABLES t"
                    + " LEFT JOIN INFORMATION_SCHEMA.COLLATIONS cs ON cs.COLLATION_NAME = t.TABLE_COLLATION"
                    + " WHERE t.TABLE_SCHEMA = COALESCE(?, DATABASE())";

    /**
     * 列查询：只有 MySQL 特有的 {@code COLUMN_TYPE / EXTRA / COLUMN_KEY / COLUMN_COMMENT}
     * 才能填满 {@link ColumnDef} 的 unsigned、autoIncrement、comment 等属性。
     */
    private static final String COLUMN_SQL =
            "SELECT c.COLUMN_NAME, c.DATA_TYPE, c.COLUMN_TYPE, c.ORDINAL_POSITION, c.IS_NULLABLE,"
                    + " c.COLUMN_DEFAULT, c.COLUMN_COMMENT, c.CHARACTER_MAXIMUM_LENGTH, c.NUMERIC_PRECISION,"
                    + " c.NUMERIC_SCALE, c.CHARACTER_SET_NAME, c.COLLATION_NAME, c.EXTRA, c.COLUMN_KEY"
                    + " FROM INFORMATION_SCHEMA.COLUMNS c"
                    + " WHERE c.TABLE_SCHEMA = COALESCE(?, DATABASE()) AND c.TABLE_NAME = ?"
                    + " ORDER BY c.ORDINAL_POSITION";

    /**
     * 索引查询：{@code information_schema.STATISTICS} 的列名在 MySQL 5.7 / 8.x 上稳定。
     */
    private static final String INDEX_SQL =
            "SELECT s.TABLE_NAME, s.INDEX_NAME, s.SEQ_IN_INDEX, s.COLUMN_NAME, s.NON_UNIQUE, s.INDEX_TYPE,"
                    + " s.INDEX_COMMENT, s.COLLATION"
                    + " FROM INFORMATION_SCHEMA.STATISTICS s"
                    + " WHERE s.TABLE_SCHEMA = COALESCE(?, DATABASE()) AND s.TABLE_NAME = ?";

    /**
     * 构造方法（无表名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected MysqlMetaTable(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
     * 构造方法（带表名上下文）。
     *
     * @param metaData  元数据入口
     * @param engine    引擎实例
     * @param tableName 表名
     */
    protected MysqlMetaTable(AbstractMetaData metaData, Engine engine, String tableName) {
        super(metaData, engine, tableName);
    }

    /**
     * 列出当前库下的所有基表。
     *
     * @return 表定义列表（表级属性完整，列与索引明细为空）
     * @throws IllegalStateException 查询失败
     */
    @Override
    public List<TableDef> list() {
        String sql = TABLE_SQL + " AND t.TABLE_TYPE = 'BASE TABLE' ORDER BY t.TABLE_NAME";
        return MysqlMetaData.query(engine, "列出表", sql,
                MysqlMetaData.args(MysqlMetaData.resolveSchema(metaData)), this::mapTable);
    }

    /**
     * 获取当前表的完整结构定义（表属性 + 列 + 主键 + 索引）。
     *
     * @return 表定义；表不存在时返回 {@code null}
     * @throws IllegalStateException 未指定表名或查询失败
     */
    @Override
    public TableDef get() {
        if (tableName == null) {
            throw new IllegalStateException("未指定表名，请先调用 meta().table(String)");
        }
        String schema = MysqlMetaData.resolveSchema(metaData);
        TableDef def = MysqlMetaData.queryOne(engine, "查询表结构 " + tableName,
                TABLE_SQL + " AND t.TABLE_NAME = ?", MysqlMetaData.args(schema, tableName), this::mapTable);
        if (def == null) {
            return null;
        }
        List<IndexMetadata> indexes = readIndexes(schema, tableName, null);
        def.setIndexes(indexes);
        Set<String> pkColumns = new LinkedHashSet<>();
        for (IndexMetadata index : indexes) {
            if (index.isPrimary() && index.getColumns() != null) {
                pkColumns.addAll(index.getColumns());
            }
        }
        def.setPrimaryKeys(pkColumns.toArray(new String[0]));
        List<ColumnDef> columns = readColumns(schema, tableName);
        for (ColumnDef column : columns) {
            column.setPrimaryKey(pkColumns.contains(column.getName()));
        }
        def.setColumns(columns);
        return def;
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
        return MysqlMetaData.execute(engine, "删除表", "DROP TABLE IF EXISTS " + quote(tableName), List.of());
    }

    @Override
    public boolean rename(String newName) {
        if (tableName == null) {
            throw new IllegalStateException("未指定原表名");
        }
        return MysqlMetaData.execute(engine, "重命名表",
                "RENAME TABLE " + quote(tableName) + " TO " + quote(newName), List.of());
    }

    /**
     * 读取列定义。
     *
     * @param schema 库名，可为 {@code null}（取 {@code DATABASE()}）
     * @param table  表名
     * @return 列定义列表，按 {@code ORDINAL_POSITION} 升序
     * @throws IllegalStateException 查询失败
     */
    protected List<ColumnDef> readColumns(String schema, String table) {
        return MysqlMetaData.query(engine, "查询列 " + table, COLUMN_SQL,
                MysqlMetaData.args(schema, table), MysqlMetaTable::mapColumn);
    }

    /**
     * 读取索引定义（MySQL 主键即名为 {@code PRIMARY} 的聚簇索引）。
     *
     * @param schema    库名，可为 {@code null}
     * @param table     表名
     * @param indexName 索引名，{@code null} 表示该表全部索引
     * @return 索引列表，按索引名与列序号有序
     * @throws IllegalStateException 查询失败
     */
    protected List<IndexMetadata> readIndexes(String schema, String table, String indexName) {
        String sql = indexName == null
                ? INDEX_SQL + " ORDER BY s.INDEX_NAME, s.SEQ_IN_INDEX"
                : INDEX_SQL + " AND s.INDEX_NAME = ? ORDER BY s.INDEX_NAME, s.SEQ_IN_INDEX";
        List<Object> args = indexName == null
                ? MysqlMetaData.args(schema, table)
                : MysqlMetaData.args(schema, table, indexName);
        List<IndexRow> rows = MysqlMetaData.query(engine, "查询索引 " + table, sql, args, IndexRow::read);
        Map<String, IndexMetadata> grouped = new LinkedHashMap<>();
        for (IndexRow row : rows) {
            IndexMetadata meta = grouped.get(row.indexName);
            if (meta == null) {
                meta = new IndexMetadata();
                meta.setName(row.indexName);
                meta.setTableName(row.tableName);
                meta.setPrimary("PRIMARY".equals(row.indexName));
                meta.setUnique(!row.nonUnique);
                meta.setType(row.indexType);
                meta.setComment(row.indexComment);
                meta.setPosition(row.seqInIndex);
                meta.setColumns(new ArrayList<>());
                grouped.put(row.indexName, meta);
            }
            if (row.columnName != null) {
                meta.getColumns().add(row.columnName);
                if (meta.getColumnName() == null) {
                    meta.setColumnName(row.columnName);
                }
            }
            if (meta.getSortDirection() == null) {
                meta.setSortDirection(resolveSortDirection(row.collation));
            }
        }
        // MySQL 的 information_schema 不提供索引可见性（仅 SHOW INDEX / mysql.indexes 提供），
        // 故 IndexMetadata.invisible 保持默认 false，不伪造"不可见索引"信息。
        return new ArrayList<>(grouped.values());
    }

    /**
     * 引用标识符，使用 MySQL 反引号并做注入校验。
     *
     * @param name 标识符
     * @return 引用后的标识符
     */
    String quote(String name) {
        return MysqlMetaData.quote(name);
    }

    /**
     * 执行 DDL。
     *
     * @param sql SQL 语句
     * @return 是否成功
     * @throws IllegalStateException 执行失败
     */
    boolean executeUpdate(String sql) {
        return MysqlMetaData.execute(engine, "执行 SQL", sql, List.of());
    }

    /**
     * 按表名重读真实结构，供构建器回填返回值。
     *
     * @param table 表名
     * @return 表定义，表不存在时为 {@code null}
     */
    TableDef reload(String table) {
        return new MysqlMetaTable(metaData, engine, table).get();
    }

    /**
     * 表级结果集映射。
     *
     * @param rs 结果集当前行
     * @return 表定义
     * @throws SQLException 读取失败
     */
    private TableDef mapTable(ResultSet rs) throws SQLException {
        TableDef def = new TableDef();
        def.setCatalog(rs.getString("TABLE_CATALOG"));
        def.setSchema(rs.getString("TABLE_SCHEMA"));
        def.setName(rs.getString("TABLE_NAME"));
        def.setType(rs.getString("TABLE_TYPE"));
        def.setEngine(MysqlMetaData.trimToNull(rs.getString("ENGINE")));
        def.setCollate(MysqlMetaData.trimToNull(rs.getString("TABLE_COLLATION")));
        def.setCharset(MysqlMetaData.trimToNull(rs.getString("CHARACTER_SET_NAME")));
        def.setComment(MysqlMetaData.trimToNull(rs.getString("TABLE_COMMENT")));
        def.setCreateTime(toDate(rs.getTimestamp("CREATE_TIME")));
        def.setUpdateTime(toDate(rs.getTimestamp("UPDATE_TIME")));
        long rows = rs.getLong("TABLE_ROWS");
        def.setRowCount(rs.wasNull() ? null : rows);
        return def;
    }

    /**
     * 列结果集映射。
     *
     * @param rs 结果集当前行
     * @return 列定义
     * @throws SQLException 读取失败
     */
    private static ColumnDef mapColumn(ResultSet rs) throws SQLException {
        ColumnDef col = new ColumnDef();
        String columnType = MysqlMetaData.trimToNull(rs.getString("COLUMN_TYPE"));
        col.setName(rs.getString("COLUMN_NAME"));
        col.setType(columnType != null ? columnType : MysqlMetaData.trimToNull(rs.getString("DATA_TYPE")));
        col.setNullable("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
        col.setDefaultValue(MysqlMetaData.trimToNull(rs.getString("COLUMN_DEFAULT")));
        col.setComment(MysqlMetaData.trimToNull(rs.getString("COLUMN_COMMENT")));
        col.setCharset(MysqlMetaData.trimToNull(rs.getString("CHARACTER_SET_NAME")));
        col.setCollation(MysqlMetaData.trimToNull(rs.getString("COLLATION_NAME")));
        col.setLength(toLong(rs, "CHARACTER_MAXIMUM_LENGTH"));
        Integer precision = toInteger(rs, "NUMERIC_PRECISION");
        col.setPrecision(precision);
        col.setScale(toInteger(rs, "NUMERIC_SCALE"));
        col.setOrdinalPosition(toInteger(rs, "ORDINAL_POSITION"));
        String extra = rs.getString("EXTRA");
        col.setAutoIncrement(extra != null && extra.toLowerCase().contains("auto_increment"));
        col.setUnsigned(columnType != null && columnType.toLowerCase().contains("unsigned"));
        col.setPrimaryKey("PRI".equals(rs.getString("COLUMN_KEY")));
        return col;
    }

    /**
     * 索引排序方向：{@code COLLATION} 为 {@code A}（升序）/ {@code D}（降序）/ {@code NULL}（不适用）。
     *
     * @param collation 原始值
     * @return ASC / DESC，无法判定时 {@code null}
     */
    private static String resolveSortDirection(String collation) {
        if ("A".equalsIgnoreCase(collation)) {
            return "ASC";
        }
        if ("D".equalsIgnoreCase(collation)) {
            return "DESC";
        }
        return null;
    }

    /**
     * 读取可空数值列为 {@link Long}。
     *
     * @param rs    结果集
     * @param label 列名
     * @return 数值，列为 {@code NULL} 时返回 {@code null}
     * @throws SQLException 读取失败
     */
    private static Long toLong(ResultSet rs, String label) throws SQLException {
        long value = rs.getLong(label);
        return rs.wasNull() ? null : value;
    }

    /**
     * 读取可空数值列为 {@link Integer}。
     *
     * @param rs    结果集
     * @param label 列名
     * @return 数值，列为 {@code NULL} 时返回 {@code null}
     * @throws SQLException 读取失败
     */
    private static Integer toInteger(ResultSet rs, String label) throws SQLException {
        int value = rs.getInt(label);
        return rs.wasNull() ? null : value;
    }

    /**
     * {@link Timestamp} 转 {@link java.util.Date}。
     *
     * @param ts 时间戳
     * @return 日期，入参为 {@code null} 时返回 {@code null}
     */
    private static java.util.Date toDate(Timestamp ts) {
        return ts == null ? null : new java.util.Date(ts.getTime());
    }

    /**
     * {@code STATISTICS} 单行原始值。
     *
     * @author CH
     * @since 4.0.0
     */
    private record IndexRow(String tableName, String indexName, Integer seqInIndex, String columnName,
                            boolean nonUnique, String indexType, String indexComment, String collation) {

        /**
         * 读取结果集当前行。
         *
         * @param rs 结果集
         * @return 行值
         * @throws SQLException 读取失败
         */
        static IndexRow read(ResultSet rs) throws SQLException {
            return new IndexRow(rs.getString("TABLE_NAME"), rs.getString("INDEX_NAME"),
                    toInteger(rs, "SEQ_IN_INDEX"), rs.getString("COLUMN_NAME"),
                    rs.getInt("NON_UNIQUE") != 0, MysqlMetaData.trimToNull(rs.getString("INDEX_TYPE")),
                    MysqlMetaData.trimToNull(rs.getString("INDEX_COMMENT")), rs.getString("COLLATION"));
        }
    }

    // ==================== MySQL 建表构建器 ====================

    /**
     * MySQL 建表链式构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class MysqlTableCreateBuilder implements TableCreateBuilder {

        /**
         * 所属表元数据入口
         */
        private final MysqlMetaTable metaTable;
        /**
         * 表名
         */
        private final String tableName;
        /**
         * 列定义
         */
        private final List<ColumnDef> columns = new ArrayList<>();
        /**
         * 联合主键列
         */
        private final List<String> primaryKeys = new ArrayList<>();
        /**
         * 表注释
         */
        private String comment;
        /**
         * 存储引擎
         */
        private String engineName;
        /**
         * 字符集
         */
        private String charset;
        /**
         * 排序规则
         */
        private String collate;

        MysqlTableCreateBuilder(MysqlMetaTable metaTable, String tableName) {
            this.metaTable = metaTable;
            this.tableName = tableName;
        }

        @Override
        public TableCreateBuilder column(String name, String type) {
            columns.add(new ColumnDef().setName(name).setType(MysqlMetaData.checkDdlFragment("列类型", type)));
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
                primaryKeys.add(col.getName());
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
                columns.get(columns.size() - 1).setDefaultValue(MysqlMetaData.checkDdlFragment("默认值", val));
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
            this.engineName = MysqlMetaData.checkDdlFragment("存储引擎", engine);
            return this;
        }

        @Override
        public TableCreateBuilder charset(String charset) {
            this.charset = MysqlMetaData.checkDdlFragment("字符集", charset);
            return this;
        }

        @Override
        public TableCreateBuilder collate(String collate) {
            this.collate = MysqlMetaData.checkDdlFragment("排序规则", collate);
            return this;
        }

        @Override
        public TableDef execute() {
            if (columns.isEmpty()) {
                throw new IllegalStateException("建表至少需要一个列");
            }
            List<String> definitions = new ArrayList<>();
            for (ColumnDef col : columns) {
                StringBuilder line = new StringBuilder();
                line.append(metaTable.quote(col.getName())).append(' ').append(col.getType());
                if (col.isUnsigned()) {
                    line.append(" UNSIGNED");
                }
                if (col.isAutoIncrement()) {
                    line.append(" AUTO_INCREMENT");
                }
                if (!col.isNullable()) {
                    line.append(" NOT NULL");
                }
                if (col.getDefaultValue() != null && !col.getDefaultValue().isEmpty()) {
                    line.append(" DEFAULT ").append(col.getDefaultValue());
                }
                if (col.getComment() != null && !col.getComment().isEmpty()) {
                    line.append(" COMMENT '").append(MysqlMetaData.escapeSql(col.getComment())).append("'");
                }
                definitions.add(line.toString());
            }
            if (!primaryKeys.isEmpty()) {
                List<String> quoted = new ArrayList<>();
                for (String pk : primaryKeys) {
                    quoted.add(metaTable.quote(pk));
                }
                definitions.add("PRIMARY KEY (" + String.join(", ", quoted) + ")");
            }
            StringBuilder sql = new StringBuilder("CREATE TABLE ")
                    .append(metaTable.quote(tableName)).append(" (\n  ")
                    .append(String.join(",\n  ", definitions)).append("\n)");
            if (engineName != null) {
                sql.append(" ENGINE=").append(engineName);
            }
            if (charset != null) {
                sql.append(" DEFAULT CHARSET=").append(charset);
            }
            if (collate != null) {
                sql.append(" COLLATE=").append(collate);
            }
            if (comment != null) {
                sql.append(" COMMENT='").append(MysqlMetaData.escapeSql(comment)).append("'");
            }
            metaTable.executeUpdate(sql.toString());
            // 以 information_schema 读回的真实结构作为返回值
            return metaTable.reload(tableName);
        }
    }

    // ==================== MySQL 改表构建器 ====================

    /**
     * MySQL 改表链式构建器。
     * <p>
     * 变更子句按下标登记，列变更通过下标回写，避免"永远改最后一条"导致的语句错位。
     * </p>
     *
     * @author CH
     * @since 4.0.0
     */
    private static class MysqlTableAlterBuilder implements TableAlterBuilder {

        /**
         * 所属表元数据入口
         */
        private final MysqlMetaTable metaTable;
        /**
         * 变更子句
         */
        private final List<String> clauses = new ArrayList<>();

        MysqlTableAlterBuilder(MysqlMetaTable metaTable) {
            this.metaTable = metaTable;
        }

        int addSql(String sql) {
            clauses.add(sql);
            return clauses.size() - 1;
        }

        void replaceSql(int index, String sql) {
            clauses.set(index, sql);
        }

        MysqlMetaTable metaTable() {
            return metaTable;
        }

        @Override
        public AlterColumnBuilder addColumn(String name, String type) {
            return new MysqlAlterColumnBuilder(this, "ADD COLUMN", name, MysqlMetaData.checkDdlFragment("列类型", type));
        }

        @Override
        public TableAlterBuilder dropColumn(String columnName) {
            addSql("DROP COLUMN " + metaTable.quote(columnName));
            return this;
        }

        @Override
        public AlterColumnBuilder modifyColumn(String columnName, String newType) {
            return new MysqlAlterColumnBuilder(this, "MODIFY COLUMN", columnName,
                    MysqlMetaData.checkDdlFragment("列类型", newType));
        }

        @Override
        public TableAlterBuilder addPrimaryKey(String... columns) {
            List<String> quoted = new ArrayList<>();
            for (String column : columns) {
                quoted.add(metaTable.quote(column));
            }
            addSql("ADD PRIMARY KEY (" + String.join(", ", quoted) + ")");
            return this;
        }

        @Override
        public TableAlterBuilder dropPrimaryKey() {
            addSql("DROP PRIMARY KEY");
            return this;
        }

        @Override
        public AlterIndexBuilder addIndex(String indexName) {
            return new MysqlAlterIndexBuilder(this, indexName);
        }

        @Override
        public TableAlterBuilder dropIndex(String indexName) {
            addSql("DROP INDEX " + metaTable.quote(indexName));
            return this;
        }

        @Override
        public AlterForeignKeyBuilder addForeignKey(String fkName) {
            return new MysqlAlterForeignKeyBuilder(this, fkName);
        }

        @Override
        public TableAlterBuilder dropForeignKey(String fkName) {
            addSql("DROP FOREIGN KEY " + metaTable.quote(fkName));
            return this;
        }

        @Override
        public TableAlterBuilder renameTo(String newName) {
            addSql("RENAME TO " + metaTable.quote(newName));
            return this;
        }

        @Override
        public TableDef execute() {
            if (clauses.isEmpty()) {
                throw new IllegalStateException("没有需要执行的变更");
            }
            if (metaTable.tableName == null) {
                throw new IllegalStateException("未指定表名");
            }
            String sql = "ALTER TABLE " + metaTable.quote(metaTable.tableName) + "\n  "
                    + String.join(",\n  ", clauses);
            metaTable.executeUpdate(sql);
            return metaTable.get();
        }
    }

    /**
     * MySQL 列变更构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class MysqlAlterColumnBuilder implements AlterColumnBuilder {

        /**
         * 父级改表构建器
         */
        private final MysqlTableAlterBuilder parent;
        /**
         * 子句下标
         */
        private final int clauseIndex;
        /**
         * 动作关键字（ADD COLUMN / MODIFY COLUMN）
         */
        private final String keyword;
        /**
         * 列名
         */
        private final String columnName;
        /**
         * 列类型
         */
        private final String columnType;
        /**
         * 非空标记
         */
        private boolean notNull;
        /**
         * 默认值
         */
        private String defaultValue;
        /**
         * 注释
         */
        private String comment;
        /**
         * 位置：某列之后
         */
        private String after;
        /**
         * 位置：首列
         */
        private boolean first;

        MysqlAlterColumnBuilder(MysqlTableAlterBuilder parent, String keyword, String columnName, String columnType) {
            this.parent = parent;
            this.keyword = keyword;
            this.columnName = columnName;
            this.columnType = columnType;
            this.clauseIndex = parent.addSql(rebuild());
        }

        @Override
        public AlterColumnBuilder notNull() {
            this.notNull = true;
            refresh();
            return this;
        }

        @Override
        public AlterColumnBuilder defaultValue(String val) {
            this.defaultValue = MysqlMetaData.checkDdlFragment("默认值", val);
            refresh();
            return this;
        }

        @Override
        public AlterColumnBuilder comment(String comment) {
            this.comment = comment;
            refresh();
            return this;
        }

        @Override
        public AlterColumnBuilder after(String columnName) {
            this.after = columnName;
            refresh();
            return this;
        }

        @Override
        public AlterColumnBuilder first() {
            this.first = true;
            refresh();
            return this;
        }

        @Override
        public TableAlterBuilder execute() {
            refresh();
            return parent;
        }

        private void refresh() {
            parent.replaceSql(clauseIndex, rebuild());
        }

        private String rebuild() {
            MysqlMetaTable target = parent.metaTable();
            StringBuilder sb = new StringBuilder(keyword)
                    .append(' ').append(target.quote(columnName)).append(' ').append(columnType);
            if (notNull) {
                sb.append(" NOT NULL");
            }
            if (defaultValue != null && !defaultValue.isEmpty()) {
                sb.append(" DEFAULT ").append(defaultValue);
            }
            if (first) {
                sb.append(" FIRST");
            } else if (after != null && !after.isEmpty()) {
                sb.append(" AFTER ").append(target.quote(after));
            }
            if (comment != null && !comment.isEmpty()) {
                sb.append(" COMMENT '").append(MysqlMetaData.escapeSql(comment)).append("'");
            }
            return sb.toString();
        }
    }

    /**
     * MySQL 索引变更构建器。
     * <p>
     * 核心契约 {@link AlterIndexBuilder} 只有列、唯一性、类型三个入口，
     * 因此改表路径不承载索引注释与可见性：需要这两项请走
     * {@link MysqlMetaIndex#create(String)} 的 {@code comment(String)} / {@code visible(boolean)}。
     * </p>
     *
     * @author CH
     * @since 4.0.0
     */
    private static class MysqlAlterIndexBuilder implements AlterIndexBuilder {

        /**
         * 父级改表构建器
         */
        private final MysqlTableAlterBuilder parent;
        /**
         * 索引名
         */
        private final String indexName;
        /**
         * 索引列
         */
        private final List<String> cols = new ArrayList<>();
        /**
         * 唯一
         */
        private boolean unique;
        /**
         * 索引算法
         */
        private String type;

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
            this.type = MysqlMetaData.checkDdlFragment("索引类型", type);
            return this;
        }

        @Override
        public TableAlterBuilder execute() {
            parent.addSql(MysqlMetaData.addIndexClause(parent.metaTable().quote(parent.metaTable().tableName),
                    indexName, cols, unique, type, null, true));
            return parent;
        }
    }

    /**
     * MySQL 外键变更构建器。
     * <p>
     * 核心契约 {@link AlterForeignKeyBuilder} 只提供 {@code references(引用表, 引用列)}，
     * 没有本表列入口，故按"本表列与引用列同名"这一常规约定推导；
     * 两侧列名不同时请改用 {@link MysqlMetaForeignKey#add(String)}，它带 {@code column(String)}。
     * </p>
     *
     * @author CH
     * @since 4.0.0
     */
    private static class MysqlAlterForeignKeyBuilder implements AlterForeignKeyBuilder {

        /**
         * 父级改表构建器
         */
        private final MysqlTableAlterBuilder parent;
        /**
         * 外键名
         */
        private final String fkName;
        /**
         * 引用表
         */
        private String refTable;
        /**
         * 引用列
         */
        private String refColumn;
        /**
         * 删除规则
         */
        private String onDelete;
        /**
         * 更新规则
         */
        private String onUpdate;

        MysqlAlterForeignKeyBuilder(MysqlTableAlterBuilder parent, String fkName) {
            this.parent = parent;
            this.fkName = fkName;
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
            parent.addSql(MysqlMetaData.addForeignKeyClause(fkName, refColumn, refTable, refColumn,
                    onDelete == null ? null : MysqlMetaData.referentialAction("删除规则", onDelete),
                    onUpdate == null ? null : MysqlMetaData.referentialAction("更新规则", onUpdate)));
            return parent;
        }
    }
}
