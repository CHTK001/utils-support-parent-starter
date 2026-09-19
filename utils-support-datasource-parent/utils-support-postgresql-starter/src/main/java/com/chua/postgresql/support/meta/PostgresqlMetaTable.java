package com.chua.postgresql.support.meta;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL 表元数据操作。
 * <p>
 * 读取路径以 {@code pg_catalog} 为主档（{@code pg_class} + {@code pg_namespace} + {@code pg_am}
 * + {@code obj_description}），列定义用 {@code pg_attribute} 联 {@code pg_attrdef}
 * 与 {@code information_schema.columns}（后者只用于取长度/精度/标度/排序规则这类标准属性），
 * 索引用 {@code pg_index} + {@code unnest(indkey, indoption) WITH ORDINALITY} 保证列序与排序方向。
 * 模式名一律以 {@code nspname = COALESCE(?, current_schema())} 绑定过滤，
 * 参数为 {@code null} 时收敛到当前会话默认模式，杜绝跨模式串味与字符串拼接注入。
 * </p>
 * <p>
 * 能力边界：
 * <ul>
 *   <li>PostgreSQL 字典没有建表/改表时间戳，{@code createTime} / {@code updateTime} 恒为 {@code null}。</li>
 *   <li>PostgreSQL 无 {@code UNSIGNED} 语义，{@code ColumnDef.unsigned} 恒为 {@code false}。</li>
 *   <li>{@code rowCount} 取自 {@code pg_class.reltuples}，未分析过（值为 {@code -1}）时归一为 {@code null}。</li>
 *   <li>{@code TableDef.engine} 映射为表的访问方法（{@code heap} / {@code brin} 等），
 *       {@code charset} / {@code collate} 取当前数据库级默认值（PostgreSQL 没有表级字符集）。</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PostgresqlMetaTable extends AbstractMetaTable {

    /**
     * 表级属性查询。
     */
    private static final String TABLE_SQL =
            "SELECT current_database() AS table_catalog, n.nspname AS table_schema, c.relname AS table_name,"
                    + " CASE c.relkind WHEN 'r' THEN 'BASE TABLE' WHEN 'p' THEN 'BASE TABLE'"
                    + " WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW'"
                    + " WHEN 'f' THEN 'FOREIGN TABLE' WHEN 't' THEN 'TOAST TABLE' ELSE c.relkind::text END"
                    + " AS table_type, am.amname AS table_engine,"
                    + " pg_catalog.obj_description(c.oid, 'pg_class') AS table_comment,"
                    + " CASE WHEN c.reltuples::bigint < 0 THEN NULL ELSE c.reltuples::bigint END AS row_count,"
                    + " (SELECT pg_catalog.pg_encoding_to_char(d.encoding) FROM pg_catalog.pg_database d"
                    + "  WHERE d.datname = current_database()) AS charset,"
                    + " (SELECT d.datcollate FROM pg_catalog.pg_database d WHERE d.datname = current_database())"
                    + " AS collation"
                    + " FROM pg_catalog.pg_class c"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                    + " LEFT JOIN pg_catalog.pg_am am ON am.oid = c.relam"
                    + " WHERE n.nspname = COALESCE(?, current_schema())";

    /**
     * 列查询：{@code format_type} 给出可直接回填 DDL 的类型文本。
     */
    private static final String COLUMN_SQL =
            "SELECT a.attname AS column_name,"
                    + " pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type,"
                    + " a.attnum AS ordinal_position, NOT a.attnotnull AS is_nullable,"
                    + " pg_catalog.pg_get_expr(d.adbin, d.adrelid) AS column_default,"
                    + " a.attidentity AS identity,"
                    + " pg_catalog.col_description(a.attrelid, a.attnum) AS column_comment,"
                    + " isc.character_maximum_length AS char_length, isc.numeric_precision AS num_precision,"
                    + " isc.numeric_scale AS num_scale, isc.collation_name AS collation_name"
                    + " FROM pg_catalog.pg_attribute a"
                    + " JOIN pg_catalog.pg_class t ON t.oid = a.attrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace"
                    + " LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum"
                    + " LEFT JOIN information_schema.columns isc"
                    + " ON isc.table_schema = n.nspname AND isc.table_name = t.relname AND isc.column_name = a.attname"
                    + " WHERE n.nspname = COALESCE(?, current_schema()) AND t.relname = ?"
                    + " AND a.attnum > 0 AND NOT a.attisdropped"
                    + " ORDER BY a.attnum";

    /**
     * 索引查询：{@code indkey} 给列号，{@code indoption} 低位为降序标记，{@code WITH ORDINALITY} 保列序。
     */
    private static final String INDEX_SQL =
            "SELECT t.relname AS table_name, ci.relname AS index_name, ix.indisunique AS is_unique,"
                    + " ix.indisprimary AS is_primary, ix.indisvalid AS is_valid,"
                    + " iam.amname AS index_type,"
                    + " pg_catalog.obj_description(ci.oid, 'pg_class') AS index_comment,"
                    + " a.attname AS column_name, k.ord::int AS seq_in_index,"
                    + " (k.opt & 1) AS is_desc"
                    + " FROM pg_catalog.pg_index ix"
                    + " JOIN pg_catalog.pg_class t ON t.oid = ix.indrelid"
                    + " JOIN pg_catalog.pg_class ci ON ci.oid = ix.indexrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace"
                    + " LEFT JOIN pg_catalog.pg_am iam ON iam.oid = ci.relam"
                    + " JOIN LATERAL unnest(ix.indkey, ix.indoption) WITH ORDINALITY AS k(attnum, opt, ord)"
                    + " ON true"
                    + " LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = ix.indrelid AND a.attnum = k.attnum"
                    + " WHERE n.nspname = COALESCE(?, current_schema()) AND t.relname = ?";

    /**
     * 主键约束名查询，供 {@code dropPrimaryKey()} 使用（PostgreSQL 不支持无名的 {@code DROP PRIMARY KEY}）。
     */
    private static final String PRIMARY_KEY_NAME_SQL =
            "SELECT con.conname FROM pg_catalog.pg_constraint con"
                    + " JOIN pg_catalog.pg_class t ON t.oid = con.conrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace"
                    + " WHERE con.contype = 'p' AND n.nspname = COALESCE(?, current_schema()) AND t.relname = ?";

    /**
     * 构造方法（无表名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected PostgresqlMetaTable(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
     * 构造方法（带表名上下文）。
     *
     * @param metaData  元数据入口
     * @param engine    引擎实例
     * @param tableName 表名
     */
    protected PostgresqlMetaTable(AbstractMetaData metaData, Engine engine, String tableName) {
        super(metaData, engine, tableName);
    }

    /**
     * 列出当前模式下的所有基表（含分区主表）。
     *
     * @return 表定义列表（表级属性完整，列与索引明细由 {@link #get()} 提供）
     * @throws IllegalStateException 查询失败
     */
    @Override
    public List<TableDef> list() {
        String sql = TABLE_SQL + " AND c.relkind IN ('r', 'p') ORDER BY c.relname";
        return PostgresqlMetaData.query(metaData, "列出表", sql,
                PostgresqlMetaData.args(PostgresqlMetaData.resolveSchema(metaData)), this::mapTable);
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
        String schema = PostgresqlMetaData.resolveSchema(metaData);
        TableDef def = PostgresqlMetaData.queryOne(metaData, "查询表结构 " + tableName,
                TABLE_SQL + " AND c.relname = ?", PostgresqlMetaData.args(schema, tableName), this::mapTable);
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
        return new PostgresTableCreateBuilder(this, tableName);
    }

    @Override
    public TableAlterBuilder alter() {
        return new PostgresTableAlterBuilder(this);
    }

    /**
     * 删除当前表。
     *
     * @return 是否成功
     * @throws IllegalStateException 未指定表名或执行失败
     */
    @Override
    public boolean drop() {
        if (tableName == null) {
            throw new IllegalStateException("未指定表名");
        }
        return PostgresqlMetaData.execute(metaData, "删除表", "DROP TABLE IF EXISTS " + quote(tableName),
                PostgresqlMetaData.args());
    }

    /**
     * 重命名当前表。
     *
     * @param newName 新表名
     * @return 是否成功
     * @throws IllegalStateException 未指定表名或执行失败
     */
    @Override
    public boolean rename(String newName) {
        if (tableName == null) {
            throw new IllegalStateException("未指定原表名");
        }
        return PostgresqlMetaData.execute(metaData, "重命名表",
                "ALTER TABLE " + quote(tableName) + " RENAME TO " + quote(newName), PostgresqlMetaData.args());
    }

    /**
     * 读取列定义。
     *
     * @param schema 模式名，可为 {@code null}（取 {@code current_schema()}）
     * @param table  表名
     * @return 列定义列表，按列序升序
     * @throws IllegalStateException 查询失败
     */
    protected List<ColumnDef> readColumns(String schema, String table) {
        return PostgresqlMetaData.query(metaData, "查询列 " + table, COLUMN_SQL,
                PostgresqlMetaData.args(schema, table), PostgresqlMetaTable::mapColumn);
    }

    /**
     * 读取索引定义（PostgreSQL 主键即 {@code indisprimary} 的唯一索引）。
     *
     * @param schema    模式名，可为 {@code null}
     * @param table     表名
     * @param indexName 索引名，{@code null} 表示该表全部索引
     * @return 索引列表，按索引名与列序号有序
     * @throws IllegalStateException 查询失败
     */
    protected List<IndexMetadata> readIndexes(String schema, String table, String indexName) {
        String sql = indexName == null ? INDEX_SQL + " ORDER BY ci.relname, k.ord"
                : INDEX_SQL + " AND ci.relname = ? ORDER BY ci.relname, k.ord";
        List<Object> args = indexName == null
                ? PostgresqlMetaData.args(schema, table)
                : PostgresqlMetaData.args(schema, table, indexName);
        List<IndexRow> rows = PostgresqlMetaData.query(metaData, "查询索引 " + table, sql, args, IndexRow::read);
        Map<String, IndexMetadata> grouped = new LinkedHashMap<>();
        for (IndexRow row : rows) {
            IndexMetadata meta = grouped.get(row.indexName);
            if (meta == null) {
                meta = new IndexMetadata();
                meta.setName(row.indexName);
                meta.setTableName(row.tableName);
                meta.setPrimary(row.primary);
                meta.setUnique(row.unique);
                meta.setType(row.indexType);
                meta.setComment(row.indexComment);
                meta.setPosition(row.seqInIndex);
                meta.setColumns(new ArrayList<>());
                meta.setSortDirection(row.descending ? "DESC" : "ASC");
                grouped.put(row.indexName, meta);
            }
            if (row.columnName != null) {
                meta.getColumns().add(row.columnName);
                if (meta.getColumnName() == null) {
                    meta.setColumnName(row.columnName);
                }
            }
        }
        // PostgreSQL 没有不可见索引概念；indisvalid 在 IndexMetadata 中没有承载字段，
        // 故 invisible 保持默认 false，不借用有效性字段伪造语义。
        return new ArrayList<>(grouped.values());
    }

    /**
     * 查询主键约束名。
     *
     * @param table 表名
     * @return 主键约束名，无主键时 {@code null}
     * @throws IllegalStateException 查询失败
     */
    String primaryKeyConstraintName(String table) {
        return PostgresqlMetaData.queryOne(metaData, "查询主键约束 " + table, PRIMARY_KEY_NAME_SQL,
                PostgresqlMetaData.args(PostgresqlMetaData.resolveSchema(metaData), table),
                rs -> PostgresqlMetaData.trimToNull(rs.getString("conname")));
    }

    /**
     * 引用标识符。
     *
     * @param name 标识符
     * @return 引用后的标识符
     */
    String quote(String name) {
        return PostgresqlMetaData.quote(name);
    }

    /**
     * 执行 DDL。
     *
     * @param sql SQL 语句
     * @return 是否成功
     * @throws IllegalStateException 执行失败
     */
    boolean executeUpdate(String sql) {
        return PostgresqlMetaData.execute(metaData, "执行 SQL", sql, PostgresqlMetaData.args());
    }

    /**
     * 按表名重读真实结构，供构建器回填返回值。
     *
     * @param table 表名
     * @return 表定义，表不存在时为 {@code null}
     */
    TableDef reload(String table) {
        return new PostgresqlMetaTable(metaData, engine, table).get();
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
        def.setCatalog(PostgresqlMetaData.trimToNull(rs.getString("table_catalog")));
        def.setSchema(rs.getString("table_schema"));
        def.setName(rs.getString("table_name"));
        def.setType(rs.getString("table_type"));
        def.setEngine(PostgresqlMetaData.trimToNull(rs.getString("table_engine")));
        def.setCharset(PostgresqlMetaData.trimToNull(rs.getString("charset")));
        def.setCollate(PostgresqlMetaData.trimToNull(rs.getString("collation")));
        def.setComment(PostgresqlMetaData.trimToNull(rs.getString("table_comment")));
        long rows = rs.getLong("row_count");
        def.setRowCount(rs.wasNull() ? null : rows);
        // PostgreSQL 字典没有创建/更新时间，保持 null
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
        col.setName(rs.getString("column_name"));
        col.setType(PostgresqlMetaData.trimToNull(rs.getString("data_type")));
        col.setNullable(rs.getBoolean("is_nullable"));
        col.setDefaultValue(PostgresqlMetaData.trimToNull(rs.getString("column_default")));
        col.setComment(PostgresqlMetaData.trimToNull(rs.getString("column_comment")));
        col.setCollation(PostgresqlMetaData.trimToNull(rs.getString("collation_name")));
        col.setLength(toLong(rs, "char_length"));
        col.setPrecision(toInteger(rs, "num_precision"));
        col.setScale(toInteger(rs, "num_scale"));
        col.setOrdinalPosition(toInteger(rs, "ordinal_position"));
        String identity = PostgresqlMetaData.trimToNull(rs.getString("identity"));
        String defaultValue = col.getDefaultValue();
        // identity 列（PG 10+）与 serial 列（旧式 nextval 默认值）都算自增；
        // ColumnDef 没有"生成列"字段，故 attgenerated 不下传，避免伪造自增语义。
        col.setAutoIncrement(identity != null
                || (defaultValue != null && defaultValue.startsWith("nextval(")));
        // PostgreSQL 无 UNSIGNED 语义
        col.setUnsigned(false);
        col.setPrimaryKey(false);
        return col;
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
     * {@code pg_index} 展开后的单行原始值。
     *
     * @author CH
     * @since 4.0.0
     */
    private record IndexRow(String tableName, String indexName, Integer seqInIndex, String columnName, boolean unique,
                            boolean primary, boolean valid, boolean descending, String indexType,
                            String indexComment) {

        /**
         * 读取结果集当前行。
         *
         * @param rs 结果集
         * @return 行值
         * @throws SQLException 读取失败
         */
        static IndexRow read(ResultSet rs) throws SQLException {
            return new IndexRow(rs.getString("table_name"), rs.getString("index_name"),
                    toInteger(rs, "seq_in_index"), rs.getString("column_name"),
                    rs.getBoolean("is_unique"), rs.getBoolean("is_primary"), rs.getBoolean("is_valid"),
                    rs.getInt("is_desc") != 0, PostgresqlMetaData.trimToNull(rs.getString("index_type")),
                    PostgresqlMetaData.trimToNull(rs.getString("index_comment")));
        }
    }

    // ==================== PostgreSQL 建表构建器 ====================

    /**
     * PostgreSQL 建表链式构建器。
     * <p>
     * 注释走 {@code COMMENT ON TABLE / COLUMN} 独立语句；
     * 自增列用 {@code GENERATED ... AS IDENTITY}（PostgreSQL 10+ 标准写法）。
     * </p>
     *
     * @author CH
     * @since 4.0.0
     */
    private static class PostgresTableCreateBuilder implements TableCreateBuilder {

        /**
         * 所属表元数据入口
         */
        private final PostgresqlMetaTable metaTable;
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
         * 访问方法
         */
        private String accessMethod;

        PostgresTableCreateBuilder(PostgresqlMetaTable metaTable, String tableName) {
            this.metaTable = metaTable;
            this.tableName = tableName;
        }

        @Override
        public TableCreateBuilder column(String name, String type) {
            columns.add(new ColumnDef().setName(name).setType(PostgresqlMetaData.checkDdlFragment("列类型", type)));
            return this;
        }

        @Override
        public TableCreateBuilder notNull() {
            last().setNullable(false);
            return this;
        }

        @Override
        public TableCreateBuilder primaryKey() {
            ColumnDef col = last();
            col.setPrimaryKey(true);
            col.setNullable(false);
            primaryKeys.add(col.getName());
            return this;
        }

        @Override
        public TableCreateBuilder autoIncrement() {
            last().setAutoIncrement(true);
            return this;
        }

        @Override
        public TableCreateBuilder unsigned() {
            throw new UnsupportedOperationException("PostgreSQL 无 UNSIGNED 语义");
        }

        @Override
        public TableCreateBuilder defaultValue(String val) {
            last().setDefaultValue(PostgresqlMetaData.checkDdlFragment("默认值", val));
            return this;
        }

        @Override
        public TableCreateBuilder comment(String val) {
            last().setComment(val);
            return this;
        }

        @Override
        public TableCreateBuilder after(String columnName) {
            throw new UnsupportedOperationException("PostgreSQL 不支持指定列位置");
        }

        @Override
        public TableCreateBuilder first() {
            throw new UnsupportedOperationException("PostgreSQL 不支持指定列位置");
        }

        @Override
        public TableCreateBuilder primaryKey(String... cols) {
            primaryKeys.addAll(List.of(cols));
            return this;
        }

        @Override
        public TableCreateBuilder commentTable(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        public TableCreateBuilder engine(String engine) {
            this.accessMethod = PostgresqlMetaData.checkDdlFragment("访问方法", engine);
            return this;
        }

        @Override
        public TableCreateBuilder charset(String charset) {
            throw new UnsupportedOperationException("PostgreSQL 字符集在建库时固定，表级不支持");
        }

        @Override
        public TableCreateBuilder collate(String collate) {
            throw new UnsupportedOperationException("PostgreSQL 排序规则按列指定，表级不支持");
        }

        /**
         * 执行建表语句，并以字典读回结果作为返回值。
         *
         * @return 落库后的表定义
         * @throws IllegalStateException 无列或执行失败
         */
        @Override
        public TableDef execute() {
            if (columns.isEmpty()) {
                throw new IllegalStateException("建表至少需要一个列");
            }
            List<String> definitions = new ArrayList<>();
            for (ColumnDef col : columns) {
                StringBuilder line = new StringBuilder()
                        .append(metaTable.quote(col.getName())).append(' ').append(col.getType());
                if (col.isAutoIncrement()) {
                    line.append(" GENERATED BY DEFAULT AS IDENTITY");
                }
                if (!col.isNullable()) {
                    line.append(" NOT NULL");
                }
                if (col.getDefaultValue() != null && !col.getDefaultValue().isEmpty()) {
                    line.append(" DEFAULT ").append(col.getDefaultValue());
                }
                if (col.getCollation() != null && !col.getCollation().isEmpty()) {
                    line.append(" COLLATE ").append(metaTable.quote(col.getCollation()));
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
            StringBuilder sql = new StringBuilder("CREATE TABLE ").append(metaTable.quote(tableName))
                    .append(" (\n  ").append(String.join(",\n  ", definitions)).append("\n)");
            if (accessMethod != null) {
                sql.append(" USING ").append(accessMethod);
            }
            metaTable.executeUpdate(sql.toString());
            applyComments();
            return metaTable.reload(tableName);
        }

        /**
         * 表与列注释在 PostgreSQL 中是独立语句。
         */
        private void applyComments() {
            String quoted = metaTable.quote(tableName);
            if (comment != null && !comment.isEmpty()) {
                metaTable.executeUpdate("COMMENT ON TABLE " + quoted + " IS '"
                        + PostgresqlMetaData.escapeSql(comment) + "'");
            }
            for (ColumnDef col : columns) {
                if (col.getComment() != null && !col.getComment().isEmpty()) {
                    metaTable.executeUpdate("COMMENT ON COLUMN " + quoted + "." + metaTable.quote(col.getName())
                            + " IS '" + PostgresqlMetaData.escapeSql(col.getComment()) + "'");
                }
            }
        }

        private ColumnDef last() {
            if (columns.isEmpty()) {
                throw new IllegalStateException("请先调用 column(String, String)");
            }
            return columns.get(columns.size() - 1);
        }
    }

    // ==================== PostgreSQL 改表构建器 ====================

    /**
     * PostgreSQL 改表链式构建器。
     * <p>
     * PostgreSQL 的一条 {@code ALTER TABLE} 只能带同一类子句的多个动作，
     * 而索引、外键、注释分别有独立语句（{@code CREATE INDEX} / {@code COMMENT ON ...}），
     * 因此这里收集"完整语句列表"并按序执行。
     * </p>
     *
     * @author CH
     * @since 4.0.0
     */
    private static class PostgresTableAlterBuilder implements TableAlterBuilder {

        /**
         * 所属表元数据入口
         */
        private final PostgresqlMetaTable metaTable;
        /**
         * 待执行语句
         */
        private final List<String> statements = new ArrayList<>();
        /**
         * 待执行的列注释：列名 -> 注释
         */
        private final Map<String, String> columnComments = new LinkedHashMap<>();

        PostgresTableAlterBuilder(PostgresqlMetaTable metaTable) {
            this.metaTable = metaTable;
        }

        String table() {
            if (metaTable.tableName == null) {
                throw new IllegalStateException("未指定表名，请先调用 meta().table(String)");
            }
            return metaTable.quote(metaTable.tableName);
        }

        void addSql(String sql) {
            statements.add(sql);
        }

        /**
         * 登记列注释，{@link #execute()} 时统一转成 {@code COMMENT ON COLUMN} 语句。
         *
         * @param columnName 列名
         * @param comment    注释
         */
        void columnComment(String columnName, String comment) {
            columnComments.put(columnName, comment);
        }

        @Override
        public AlterColumnBuilder addColumn(String name, String type) {
            return new PostgresAlterColumnBuilder(this, "ADD COLUMN", name,
                    PostgresqlMetaData.checkDdlFragment("列类型", type));
        }

        @Override
        public TableAlterBuilder dropColumn(String columnName) {
            addSql("ALTER TABLE " + table() + " DROP COLUMN " + metaTable.quote(columnName));
            return this;
        }

        @Override
        public AlterColumnBuilder modifyColumn(String columnName, String newType) {
            return new PostgresAlterColumnBuilder(this, "ALTER COLUMN", columnName,
                    PostgresqlMetaData.checkDdlFragment("列类型", newType));
        }

        @Override
        public TableAlterBuilder addPrimaryKey(String... columns) {
            List<String> quoted = new ArrayList<>();
            for (String column : columns) {
                quoted.add(metaTable.quote(column));
            }
            addSql("ALTER TABLE " + table() + " ADD PRIMARY KEY (" + String.join(", ", quoted) + ")");
            return this;
        }

        @Override
        public TableAlterBuilder dropPrimaryKey() {
            if (metaTable.tableName == null) {
                throw new IllegalStateException("未指定表名，请先调用 meta().table(String)");
            }
            String name = metaTable.primaryKeyConstraintName(metaTable.tableName);
            if (name == null) {
                throw new IllegalStateException("表 " + metaTable.tableName + " 没有主键约束");
            }
            addSql("ALTER TABLE " + table() + " DROP CONSTRAINT " + metaTable.quote(name));
            return this;
        }

        @Override
        public AlterIndexBuilder addIndex(String indexName) {
            return new PostgresAlterIndexBuilder(this, indexName);
        }

        @Override
        public TableAlterBuilder dropIndex(String indexName) {
            addSql("DROP INDEX IF EXISTS " + metaTable.quote(indexName));
            return this;
        }

        @Override
        public AlterForeignKeyBuilder addForeignKey(String fkName) {
            return new PostgresAlterForeignKeyBuilder(this, fkName);
        }

        @Override
        public TableAlterBuilder dropForeignKey(String fkName) {
            addSql("ALTER TABLE " + table() + " DROP CONSTRAINT " + metaTable.quote(fkName));
            return this;
        }

        @Override
        public TableAlterBuilder renameTo(String newName) {
            addSql("ALTER TABLE " + table() + " RENAME TO " + metaTable.quote(newName));
            return this;
        }

        /**
         * 按序执行收集到的语句。
         *
         * @return 变更后的表定义
         * @throws IllegalStateException 无变更或执行失败
         */
        @Override
        public TableDef execute() {
            if (statements.isEmpty() && columnComments.isEmpty()) {
                throw new IllegalStateException("没有需要执行的变更");
            }
            String quoted = table();
            for (Map.Entry<String, String> entry : columnComments.entrySet()) {
                statements.add("COMMENT ON COLUMN " + quoted + "." + metaTable.quote(entry.getKey()) + " IS '"
                        + PostgresqlMetaData.escapeSql(entry.getValue()) + "'");
            }
            String table = metaTable.tableName;
            for (String statement : statements) {
                metaTable.executeUpdate(statement);
            }
            return metaTable.reload(table);
        }
    }

    /**
     * PostgreSQL 列变更构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class PostgresAlterColumnBuilder implements AlterColumnBuilder {

        /**
         * 父级改表构建器
         */
        private final PostgresTableAlterBuilder parent;
        /**
         * 动作关键字（ADD COLUMN / ALTER COLUMN）
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
        private Boolean notNull;
        /**
         * 默认值
         */
        private String defaultValue;
        /**
         * 注释
         */
        private String comment;

        PostgresAlterColumnBuilder(PostgresTableAlterBuilder parent, String keyword, String columnName,
                                   String columnType) {
            this.parent = parent;
            this.keyword = keyword;
            this.columnName = columnName;
            this.columnType = columnType;
        }

        @Override
        public AlterColumnBuilder notNull() {
            this.notNull = Boolean.TRUE;
            return this;
        }

        @Override
        public AlterColumnBuilder defaultValue(String val) {
            this.defaultValue = PostgresqlMetaData.checkDdlFragment("默认值", val);
            return this;
        }

        @Override
        public AlterColumnBuilder comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        public AlterColumnBuilder after(String columnName) {
            throw new UnsupportedOperationException("PostgreSQL 不支持指定列位置");
        }

        @Override
        public AlterColumnBuilder first() {
            throw new UnsupportedOperationException("PostgreSQL 不支持指定列位置");
        }

        /**
         * 生成并登记本列相关语句。
         *
         * @return 父级改表构建器
         */
        @Override
        public TableAlterBuilder execute() {
            String quoted = parent.table();
            String name = parent.metaTable.quote(columnName);
            if ("ADD COLUMN".equals(keyword)) {
                StringBuilder sb = new StringBuilder("ALTER TABLE ").append(quoted).append(" ADD COLUMN ")
                        .append(name).append(' ').append(columnType);
                if (Boolean.TRUE.equals(notNull)) {
                    sb.append(" NOT NULL");
                }
                if (defaultValue != null && !defaultValue.isEmpty()) {
                    sb.append(" DEFAULT ").append(defaultValue);
                }
                parent.addSql(sb.toString());
            } else {
                StringBuilder sb = new StringBuilder("ALTER TABLE ").append(quoted);
                sb.append("\n  ALTER COLUMN ").append(name).append(" TYPE ").append(columnType);
                if (defaultValue != null) {
                    sb.append("\n  ALTER COLUMN ").append(name).append(" SET DEFAULT ").append(defaultValue);
                }
                if (notNull != null) {
                    sb.append("\n  ALTER COLUMN ").append(name)
                            .append(notNull ? " SET NOT NULL" : " DROP NOT NULL");
                }
                parent.addSql(sb.toString());
            }
            if (comment != null && !comment.isEmpty()) {
                parent.columnComment(columnName, comment);
            }
            return parent;
        }
    }

    /**
     * PostgreSQL 索引变更构建器。
     * <p>
     * 索引注释通过 {@code COMMENT ON INDEX} 真实下发；
     * PostgreSQL 无不可见索引，{@code visible(false)} 显式拒绝。
     * </p>
     *
     * @author CH
     * @since 4.0.0
     */
    private static class PostgresAlterIndexBuilder implements AlterIndexBuilder {

        /**
         * 父级改表构建器
         */
        private final PostgresTableAlterBuilder parent;
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
         * 索引访问方法
         */
        private String type;

        PostgresAlterIndexBuilder(PostgresTableAlterBuilder parent, String indexName) {
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
            this.type = PostgresqlMetaData.checkDdlFragment("索引类型", type);
            return this;
        }

        /**
         * 登记 {@code CREATE INDEX} 语句。
         * <p>契约 {@link AlterIndexBuilder} 没有注释与可见性入口：
         * 索引注释请走 {@link PostgresqlMetaIndex#create(String)}（内部用 {@code COMMENT ON INDEX} 真实下发）；
         * PostgreSQL 无不可见索引概念，故不涉及可见性。</p>
         *
         * @return 父级改表构建器
         */
        @Override
        public TableAlterBuilder execute() {
            PostgresqlMetaTable metaTable = parent.metaTable;
            if (metaTable.tableName == null) {
                throw new IllegalStateException("未指定表名，请先调用 meta().table(String)");
            }
            if (cols.isEmpty()) {
                throw new IllegalStateException("索引列不能为空");
            }
            List<String> quoted = new ArrayList<>();
            for (String col : cols) {
                quoted.add(metaTable.quote(col));
            }
            StringBuilder sql = new StringBuilder("CREATE ").append(unique ? "UNIQUE " : "").append("INDEX ")
                    .append(metaTable.quote(indexName)).append(" ON ").append(metaTable.quote(metaTable.tableName));
            if (type != null) {
                sql.append(" USING ").append(type);
            }
            sql.append(" (").append(String.join(", ", quoted)).append(")");
            parent.addSql(sql.toString());
            return parent;
        }
    }

    /**
     * PostgreSQL 外键变更构建器。
     * <p>
     * 核心契约 {@link AlterForeignKeyBuilder} 只提供 {@code references(引用表, 引用列)}，
     * 没有本表列入口，故按"本表列与引用列同名"这一常规约定推导；
     * 两侧列名不同时请改用 {@link PostgresqlMetaForeignKey#add(String)}。
     * </p>
     *
     * @author CH
     * @since 4.0.0
     */
    private static class PostgresAlterForeignKeyBuilder implements AlterForeignKeyBuilder {

        /**
         * 父级改表构建器
         */
        private final PostgresTableAlterBuilder parent;
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

        PostgresAlterForeignKeyBuilder(PostgresTableAlterBuilder parent, String fkName) {
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
            parent.addSql("ALTER TABLE " + parent.table() + " " + PostgresqlMetaData.addForeignKeyClause(fkName,
                    refColumn, refTable, refColumn,
                    onDelete == null ? null : PostgresqlMetaData.referentialAction("删除规则", onDelete),
                    onUpdate == null ? null : PostgresqlMetaData.referentialAction("更新规则", onUpdate)));
            return parent;
        }
    }
}
