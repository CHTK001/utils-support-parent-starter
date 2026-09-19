package com.chua.oracle.support.index;

import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.datasource.support.index.IndexManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Oracle 创建索引链式步骤实现。
 * <p>
 * 语法为 {@code CREATE [UNIQUE] [BITMAP] INDEX 索引名 ON 表名 (列名 [, 列名])}：
 * Oracle 没有 MySQL/PostgreSQL 的 {@code USING} 子句，索引形态只能落在
 * {@code CREATE} 与 {@code INDEX} 之间的关键字上。因此索引形态由 {@link OracleIndexKind}
 * 显式建模，缺省为 {@link OracleIndexKind#NORMAL}（普通 B 树索引），
 * 只有调用方明确索取时才生成 {@code CREATE BITMAP INDEX}，
 * 也不会把调用方传入的任意算法串原样拼进语句。
 * </p>
 * <p>
 * 索引名、表名与列名属于无法使用占位符的位置，全部先经 {@link SqlName} 白名单校验
 * 再加双引号：校验发生在链式设值时，非法名字在 {@code execute} 之前就会被拒绝，
 * 不会下发任何语句。函数索引等表达式列（如 {@code UPPER(name)}）会被白名单拒绝，
 * 需要时请改用原生 DDL 执行入口。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OracleCreateIndexStep implements IndexManager.CreateIndexStep {

    /**
     * Oracle 索引形态，决定 {@code CREATE} 与 {@code INDEX} 之间的关键字。
     *
     * @author CH
     * @since 4.0.0.42
     */
    public enum OracleIndexKind {

        /**
         * 普通 B 树索引，Oracle 的默认形态，也是 OLTP 表唯一安全的选择
         */
        NORMAL("", "NORMAL", "BTREE", "NONUNIQUE"),

        /**
         * 唯一索引，约束列值不重复，与位图索引互斥
         */
        UNIQUE("UNIQUE", "UNIQUE"),

        /**
         * 位图索引，必须由调用方显式索取，缺省永不生成。
         * <p>
         * 适用前提：列基数极低（性别、状态、枚举一类，取值数远小于行数），
         * 且表以批量装载、全表扫描为主的非 OLTP/数据仓库场景。位图索引把一个键值
         * 对应的整段行锁进位图段，OLTP 表上并发 DML 会把行级锁放大成跨会话的
         * 行锁冲突甚至死锁，更新吞吐退化为串行，因此严禁在联机交易表上默认使用。
         * </p>
         * <p>
         * 数仓场景还需配合直接路径装载与 {@code NOLOGGING}、{@code PARALLEL} 及装载后
         * {@code UNUSABLE}/重建策略，本步骤只负责生成形态正确的 DDL。
         * </p>
         */
        BITMAP("BITMAP", "BITMAP");

        /**
         * {@code CREATE} 与 {@code INDEX} 之间的关键字，普通索引为空串
         */
        private final String keyword;

        /**
         * 可映射到本形态的别名（大写），含 {@code USER_INDEXES} 视图回填的取值
         */
        private final String[] aliases;

        /**
         * 构造索引形态。
         *
         * @param keyword {@code CREATE} 与 {@code INDEX} 之间的关键字，普通索引传空串
         * @param aliases 可映射到本形态的别名，需为大写形式
         */
        OracleIndexKind(String keyword, String... aliases) {
            this.keyword = keyword;
            this.aliases = aliases;
        }

        /**
         * 返回 {@code CREATE} 与 {@code INDEX} 之间的关键字。
         *
         * @return 关键字，普通索引为空串
         */
        public String keyword() {
            return keyword;
        }

        /**
         * 把调用方给定的类型名解析为索引形态，空白表示使用默认形态。
         *
         * @param value 类型名，忽略大小写与首尾空白
         * @return 对应的索引形态，{@code value} 为空白时返回 {@link #NORMAL}
         * @throws IllegalArgumentException 类型名不在白名单内
         */
        public static OracleIndexKind of(String value) {
            if (value == null || value.isBlank()) {
                return NORMAL;
            }
            String name = value.trim().toUpperCase(Locale.ROOT);
            for (OracleIndexKind kind : values()) {
                if (kind.name().equals(name)) {
                    return kind;
                }
                for (String alias : kind.aliases) {
                    if (alias.equals(name)) {
                        return kind;
                    }
                }
            }
            throw new IllegalArgumentException("不支持的 Oracle 索引类型: " + value
                    + "，可选值: NORMAL / UNIQUE / BITMAP");
        }
    }

    /**
     * 多列分隔符
     */
    private static final String COLUMN_SEPARATOR = ",";

    /**
     * 数据来源
     */
    private final DataSource dataSource;

    /**
     * 索引名称，构造时即完成白名单校验
     */
    private final String indexName;

    /**
     * 索引所属表，可为 {@code schema.table} 限定名
     */
    private String table;

    /**
     * 索引列，元素均已通过白名单校验
     */
    private final List<String> columns = new ArrayList<>();

    /**
     * 索引形态，默认普通 B 树索引
     */
    private OracleIndexKind kind = OracleIndexKind.NORMAL;

    /**
     * 构造方法，创建 Oracle 创建索引 Step 实例。
     *
     * @param dataSource 数据来源，不允许为 null
     * @param indexName  索引名称，必须通过 {@link SqlName} 白名单校验
     * @throws IllegalArgumentException 索引名为空或含非法字符
     */
    OracleCreateIndexStep(DataSource dataSource, String indexName) {
        if (dataSource == null) {
            throw new IllegalArgumentException("创建 Oracle 索引需要数据源");
        }
        this.dataSource = dataSource;
        this.indexName = SqlName.check(indexName, "索引名");
    }

    /**
     * 指定索引所属的表。
     *
     * @param table 表名，可带一段 {@code schema.} 前缀
     * @return this
     * @throws IllegalArgumentException 表名为空或含非法字符
     */
    @Override
    public OracleCreateIndexStep onTable(String table) {
        this.table = SqlName.check(table, "表名");
        return this;
    }

    /**
     * 指定索引列，覆盖此前设置的列。
     *
     * @param column 列名或逗号分隔的多列，不支持表达式列
     * @return this
     * @throws IllegalArgumentException 列为空、含非法字符或为表达式
     */
    @Override
    public OracleCreateIndexStep field(String column) {
        return onColumn(column);
    }

    /**
     * 指定索引列，覆盖此前设置的列。
     *
     * @param column 列名或逗号分隔的多列，不支持表达式列
     * @return this
     * @throws IllegalArgumentException 列为空、含非法字符或为表达式
     */
    @Override
    public OracleCreateIndexStep onColumn(String column) {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("列名不能为空, 索引名: " + indexName);
        }
        List<String> parsed = new ArrayList<>();
        for (String segment : column.split(COLUMN_SEPARATOR)) {
            String name = segment.trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("列名存在空段, 索引名: " + indexName + ", 列: " + column);
            }
            parsed.add(SqlName.check(name, "列名"));
        }
        columns.clear();
        columns.addAll(parsed);
        return this;
    }

    /**
     * 指定索引形态，取值受 {@link OracleIndexKind} 白名单约束。
     * <p>
     * 传 {@code "BITMAP"} 才会生成 {@code CREATE BITMAP INDEX}，位图索引的限制见
     * {@link OracleIndexKind#BITMAP}；未知取值直接拒绝，不会被拼进语句。
     * </p>
     *
     * @param type 索引形态名（{@code NORMAL} / {@code UNIQUE} / {@code BITMAP}），空白表示默认形态
     * @return this
     * @throws IllegalArgumentException 类型名不在白名单内
     */
    @Override
    public OracleCreateIndexStep type(String type) {
        this.kind = OracleIndexKind.of(type);
        return this;
    }

    /**
     * 指定索引形态，等价于 {@link #type(String)}；Oracle 无 {@code USING} 子句，
     * 该入参只会映射到 {@code CREATE} 后的关键字。
     *
     * @param algorithm 索引形态名（{@code NORMAL} / {@code UNIQUE} / {@code BITMAP}），空白表示默认形态
     * @return this
     * @throws IllegalArgumentException 类型名不在白名单内
     */
    @Override
    public OracleCreateIndexStep using(String algorithm) {
        return type(algorithm);
    }

    /**
     * 显式指定索引形态。
     *
     * @param indexKind 索引形态，不允许为 null
     * @return this
     * @throws IllegalArgumentException 形态为 null
     */
    public OracleCreateIndexStep kind(OracleIndexKind indexKind) {
        if (indexKind == null) {
            throw new IllegalArgumentException("索引形态不能为空, 索引名: " + indexName);
        }
        this.kind = indexKind;
        return this;
    }

    /**
     * 显式索取位图索引。
     * <p>
     * 仅适用于低基数列、以批量装载为主的数仓表，且该表不得有并发 DML；
     * 在 OLTP 表上调用会造成严重的行锁放大，详见 {@link OracleIndexKind#BITMAP}。
     * </p>
     *
     * @return this
     */
    public OracleCreateIndexStep bitmap() {
        return kind(OracleIndexKind.BITMAP);
    }

    /**
     * 生成待执行的 DDL 语句，所有标识符均已校验并引用。
     *
     * @return 完整的 {@code CREATE INDEX} 语句
     * @throws IllegalArgumentException 未指定表或列
     */
    private String buildSql() {
        if (table == null) {
            throw new IllegalArgumentException("索引 " + indexName + " 必须指定所属表");
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Oracle 索引至少需要一个列, 索引名: " + indexName);
        }
        StringBuilder sql = new StringBuilder("CREATE ");
        if (!kind.keyword().isEmpty()) {
            sql.append(kind.keyword()).append(' ');
        }
        sql.append("INDEX ").append(SqlName.quote(indexName, "索引名"))
                .append(" ON ").append(SqlName.quote(table, "表名"))
                .append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(SqlName.quote(columns.get(i), "列名"));
        }
        return sql.append(')').toString();
    }

    /**
     * 执行创建索引语句。
     * <p>
     * 建索引属于 DDL，Oracle 会隐式提交，失败时无法回滚，因此异常里带上完整语句便于定位。
     * </p>
     *
     * @throws IllegalArgumentException 未指定表或列
     * @throws IllegalStateException    JDBC 执行失败，并链入原始 {@link SQLException}
     */
    @Override
    public void execute() {
        String sql = buildSql();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("创建 Oracle 索引失败: " + indexName + ", 语句: " + sql, e);
        }
    }
}
