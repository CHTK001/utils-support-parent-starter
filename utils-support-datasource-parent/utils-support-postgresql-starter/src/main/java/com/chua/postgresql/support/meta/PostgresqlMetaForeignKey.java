package com.chua.postgresql.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.ForeignKeyCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.ForeignKeyDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaForeignKey;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * PostgreSQL 外键元数据操作。
 * <p>
 * 读取路径为 {@code pg_constraint}（{@code contype = 'f'}）联 {@code pg_class} / {@code pg_namespace}
 * / {@code pg_attribute}，用 {@code unnest(conkey, confkey) WITH ORDINALITY} 展开联合外键的列序，
 * 以 {@code n.nspname = COALESCE(?, current_schema())}（可选追加表名、约束名）过滤，全部为绑定参数。
 * 引用动作为 {@code confdeltype / confupdtype} 的单字符标记，按 SQL 标准语义展开。
 * </p>
 * <p>
 * 能力边界：
 * <ul>
 *   <li>{@code fk(String)} 传入的是约束名，故本类把该值保存在 {@code constraintName} 上，
 *       不会误当成表名去过滤 {@code list()}（核心基类第三参语义是表名）。</li>
 *   <li>联合外键在 {@link ForeignKeyDef} 中只能表达单列（模型没有列序号字段），
 *       故按列序逐列展开成多条定义，同一外键名会出现多次。</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PostgresqlMetaForeignKey extends AbstractMetaForeignKey {

    /**
     * 外键查询：模式名绑定，表名/约束名按需追加绑定条件。
     */
    private static final String FK_SQL =
            "SELECT current_database() AS table_catalog, n.nspname AS table_schema, cl.relname AS table_name,"
                    + " con.conname AS constraint_name, att.attname AS column_name,"
                    + " rc.relname AS ref_table_name, ra.attname AS ref_column_name,"
                    + " con.confdeltype AS delete_rule, con.confupdtype AS update_rule"
                    + " FROM pg_catalog.pg_constraint con"
                    + " JOIN pg_catalog.pg_class cl ON cl.oid = con.conrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = cl.relnamespace"
                    + " JOIN pg_catalog.pg_class rc ON rc.oid = con.confrelid"
                    + " JOIN LATERAL unnest(con.conkey, con.confkey) WITH ORDINALITY AS k(fk_attnum, pk_attnum, ord)"
                    + " ON true"
                    + " JOIN pg_catalog.pg_attribute att ON att.attrelid = cl.oid AND att.attnum = k.fk_attnum"
                    + " JOIN pg_catalog.pg_attribute ra ON ra.attrelid = rc.oid AND ra.attnum = k.pk_attnum"
                    + " WHERE con.contype = 'f' AND n.nspname = COALESCE(?, current_schema())";

    /**
     * 外键约束名上下文，来自 {@code MetaData#fk(String)}。
     */
    private final String constraintName;

    /**
     * 构造方法（无外键名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected PostgresqlMetaForeignKey(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
        this.constraintName = null;
    }

    /**
     * 构造方法（带外键约束名上下文）。
     * <p>
     * 核心基类第三参语义是表名，而 {@code MetaData#fk(String)} 传的是外键名，
     * 故此处不下传表名，避免 {@code list()} 被外键名错误过滤成空结果。
     * </p>
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     * @param fkName   外键约束名
     */
    protected PostgresqlMetaForeignKey(AbstractMetaData metaData, Engine engine, String fkName) {
        super(metaData, engine);
        this.constraintName = fkName;
    }

    /**
     * 列出外键定义。
     *
     * @return 外键列表；已调用 {@code onTable(String)} 时只返回该表的外键
     * @throws IllegalStateException 查询失败
     */
    @Override
    public List<ForeignKeyDef> list() {
        return readFks(null);
    }

    /**
     * 获取指定外键定义。
     *
     * @param fkName 外键名，为 {@code null} 时使用构造期上下文
     * @return 外键定义（联合外键返回其首列映射），不存在时返回 {@code null}
     * @throws IllegalStateException 未指定外键名或查询失败
     */
    @Override
    public ForeignKeyDef get(String fkName) {
        String target = fkName != null ? fkName : constraintName;
        if (target == null) {
            throw new IllegalStateException("未指定外键名，请使用 fk(String) 或 get(String) 传入外键名");
        }
        List<ForeignKeyDef> defs = readFks(target);
        return defs.isEmpty() ? null : defs.get(0);
    }

    @Override
    public ForeignKeyCreateBuilder add(String fkName) {
        return new PostgresForeignKeyCreateBuilder(this, fkName);
    }

    /**
     * 删除外键约束（PostgreSQL 用 {@code DROP CONSTRAINT}，同时会移除约束附带的检查）。
     *
     * @param fkName 外键名
     * @return 是否成功
     * @throws IllegalStateException 未指定表名或执行失败
     */
    @Override
    public boolean drop(String fkName) {
        if (tableName == null) {
            throw new IllegalStateException("未指定表名，请先调用 onTable(String)");
        }
        String sql = "ALTER TABLE " + PostgresqlMetaData.quote(tableName) + " DROP CONSTRAINT "
                + PostgresqlMetaData.quote(fkName);
        return PostgresqlMetaData.execute(metaData, "删除外键 " + fkName, sql, PostgresqlMetaData.args());
    }

    /**
     * 按条件读取外键定义。
     *
     * @param fkName 外键名过滤，{@code null} 表示不过滤
     * @return 外键列表，按约束名与列序号升序
     * @throws IllegalStateException 查询失败
     */
    private List<ForeignKeyDef> readFks(String fkName) {
        StringBuilder sql = new StringBuilder(FK_SQL);
        List<Object> args = PostgresqlMetaData.args(PostgresqlMetaData.resolveSchema(metaData));
        if (tableName != null) {
            sql.append(" AND cl.relname = ?");
            args.add(tableName);
        }
        if (fkName != null) {
            sql.append(" AND con.conname = ?");
            args.add(fkName);
        }
        sql.append(" ORDER BY con.conname, k.ord");
        return PostgresqlMetaData.query(metaData, "查询外键" + (tableName == null ? "" : " " + tableName),
                sql.toString(), args, PostgresqlMetaForeignKey::mapForeignKey);
    }

    /**
     * 外键结果集映射，逐列对应 {@link ForeignKeyDef} 属性。
     *
     * @param rs 结果集当前行
     * @return 外键定义
     * @throws SQLException 读取失败
     */
    private static ForeignKeyDef mapForeignKey(ResultSet rs) throws SQLException {
        ForeignKeyDef def = new ForeignKeyDef();
        def.setName(rs.getString("constraint_name"));
        def.setCatalog(PostgresqlMetaData.trimToNull(rs.getString("table_catalog")));
        def.setSchema(rs.getString("table_schema"));
        def.setTableName(rs.getString("table_name"));
        def.setColumnName(rs.getString("column_name"));
        def.setRefTableName(rs.getString("ref_table_name"));
        def.setRefColumnName(rs.getString("ref_column_name"));
        def.setOnDelete(referenceAction(rs.getString("delete_rule"), "删除规则"));
        def.setOnUpdate(referenceAction(rs.getString("update_rule"), "更新规则"));
        return def;
    }

    /**
     * 把 {@code confdeltype / confupdtype} 的单字符标记翻译成 SQL 标准动作。
     *
     * @param code    单字符标记
     * @param element 用途描述，用于异常信息
     * @return 动作文本，无法识别时返回 {@code null}
     */
    private static String referenceAction(String code, String element) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        return switch (code.charAt(0)) {
            case 'a' -> "NO ACTION";
            case 'r' -> "RESTRICT";
            case 'c' -> "CASCADE";
            case 'n' -> "SET NULL";
            case 'd' -> "SET DEFAULT";
            default -> throw new IllegalStateException("PostgreSQL " + element + " 标记未知: " + code);
        };
    }

    /**
     * PostgreSQL 添加外键链式构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class PostgresForeignKeyCreateBuilder implements ForeignKeyCreateBuilder {

        /**
         * 所属外键元数据入口
         */
        private final PostgresqlMetaForeignKey metaFk;
        /**
         * 外键名
         */
        private final String fkName;
        /**
         * 当前表列名
         */
        private String columnName;
        /**
         * 引用表名
         */
        private String refTable;
        /**
         * 引用列名
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

        PostgresForeignKeyCreateBuilder(PostgresqlMetaForeignKey metaFk, String fkName) {
            this.metaFk = metaFk;
            this.fkName = fkName;
        }

        @Override
        public ForeignKeyCreateBuilder column(String columnName) {
            this.columnName = columnName;
            return this;
        }

        @Override
        public ForeignKeyCreateBuilder references(String table, String column) {
            this.refTable = table;
            this.refColumn = column;
            return this;
        }

        @Override
        public ForeignKeyCreateBuilder onDelete(String action) {
            this.onDelete = action;
            return this;
        }

        @Override
        public ForeignKeyCreateBuilder onUpdate(String action) {
            this.onUpdate = action;
            return this;
        }

        /**
         * 执行 {@code ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY}，并以字典读回结果作为返回值。
         *
         * @return 落库后的外键定义
         * @throws IllegalStateException 未指定表名/列或执行失败
         */
        @Override
        public ForeignKeyDef execute() {
            if (metaFk.tableName == null) {
                throw new IllegalStateException("未指定表名，请先调用 onTable(String)");
            }
            String clause = PostgresqlMetaData.addForeignKeyClause(fkName, columnName, refTable, refColumn,
                    onDelete == null ? null : PostgresqlMetaData.referentialAction("删除规则", onDelete),
                    onUpdate == null ? null : PostgresqlMetaData.referentialAction("更新规则", onUpdate));
            String sql = "ALTER TABLE " + PostgresqlMetaData.quote(metaFk.tableName) + " " + clause;
            PostgresqlMetaData.execute(metaFk.metaData, "创建外键 " + fkName, sql, PostgresqlMetaData.args());
            return metaFk.readForeignKey(metaFk.tableName, fkName);
        }
    }

    /**
     * 在指定表上下文中读取单个外键定义，供构建器回填返回值。
     *
     * @param table  表名
     * @param fkName 外键名
     * @return 外键定义，不存在时为 {@code null}
     */
    private ForeignKeyDef readForeignKey(String table, String fkName) {
        PostgresqlMetaForeignKey probe = new PostgresqlMetaForeignKey(metaData, engine);
        probe.tableName = table;
        return probe.get(fkName);
    }
}
