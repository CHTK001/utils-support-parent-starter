package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.ForeignKeyCreateBuilder;
import com.chua.common.support.lang.datasource.meta.MetaForeignKey;
import com.chua.common.support.lang.datasource.meta.model.ForeignKeyDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaForeignKey;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 外键元数据操作。
 * <p>
 * 读取路径为 {@code INFORMATION_SCHEMA.KEY_COLUMN_USAGE} 联
 * {@code INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS}：前者给出"外键列 -> 引用列"的逐列映射，
 * 后者给出 {@code ON DELETE / ON UPDATE} 引用动作。库名、表名、外键名全部为绑定参数，
 * 库名参数为 {@code null} 时由 SQL 侧的 {@code DATABASE()} 收敛到当前会话默认库。
 * 只保留 {@code REFERENCED_TABLE_NAME IS NOT NULL} 的行，即真正的外键约束。
 * </p>
 * <p>
 * 能力边界：
 * <ul>
 *   <li>{@code fk(String)} 传入的是外键约束名，因此本类把该值保存在 {@code constraintName} 上，
 *       不会误当成表名去过滤 {@code list()}。</li>
 *   <li>联合外键在 {@link ForeignKeyDef} 中只能表达单列（模型没有列序号字段），
 *       故按 {@code ORDINAL_POSITION} 逐列展开成多条定义，同一外键名会出现多次。</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MysqlMetaForeignKey extends AbstractMetaForeignKey {

    /**
     * 外键查询：库名绑定，表名/外键名按需追加绑定条件。
     */
    private static final String FK_SQL =
            "SELECT k.TABLE_CATALOG, k.TABLE_SCHEMA, k.TABLE_NAME, k.CONSTRAINT_NAME, k.COLUMN_NAME,"
                    + " k.REFERENCED_TABLE_SCHEMA, k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME,"
                    + " r.UPDATE_RULE, r.DELETE_RULE"
                    + " FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k"
                    + " JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS r"
                    + " ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME"
                    + " WHERE k.TABLE_SCHEMA = COALESCE(?, DATABASE()) AND k.REFERENCED_TABLE_NAME IS NOT NULL";

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
    protected MysqlMetaForeignKey(AbstractMetaData metaData, Engine engine) {
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
    protected MysqlMetaForeignKey(AbstractMetaData metaData, Engine engine, String fkName) {
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
        return new MysqlForeignKeyCreateBuilder(this, fkName);
    }

    /**
     * 删除外键约束。
     * <p>MySQL 不会连带删除为外键自动建立的索引，这里只按语义删除约束本身。</p>
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
        String sql = "ALTER TABLE " + MysqlMetaData.quote(tableName) + " DROP FOREIGN KEY "
                + MysqlMetaData.quote(fkName);
        return MysqlMetaData.execute(engine, "删除外键 " + fkName, sql, List.of());
    }

    /**
     * 按条件读取外键定义。
     *
     * @param fkName 外键名过滤，{@code null} 表示不过滤
     * @return 外键列表，按外键名与列序号升序
     * @throws IllegalStateException 查询失败
     */
    private List<ForeignKeyDef> readFks(String fkName) {
        StringBuilder sql = new StringBuilder(FK_SQL);
        List<Object> args = MysqlMetaData.args(MysqlMetaData.resolveSchema(metaData));
        if (tableName != null) {
            sql.append(" AND k.TABLE_NAME = ?");
            args.add(tableName);
        }
        if (fkName != null) {
            sql.append(" AND k.CONSTRAINT_NAME = ?");
            args.add(fkName);
        }
        sql.append(" ORDER BY k.CONSTRAINT_NAME, k.ORDINAL_POSITION");
        return MysqlMetaData.query(engine, "查询外键" + (tableName == null ? "" : " " + tableName),
                sql.toString(), args, MysqlMetaForeignKey::mapForeignKey);
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
        def.setName(rs.getString("CONSTRAINT_NAME"));
        def.setCatalog(MysqlMetaData.trimToNull(rs.getString("TABLE_CATALOG")));
        def.setSchema(rs.getString("TABLE_SCHEMA"));
        def.setTableName(rs.getString("TABLE_NAME"));
        def.setColumnName(rs.getString("COLUMN_NAME"));
        def.setRefTableName(rs.getString("REFERENCED_TABLE_NAME"));
        def.setRefColumnName(rs.getString("REFERENCED_COLUMN_NAME"));
        def.setOnDelete(MysqlMetaData.trimToNull(rs.getString("DELETE_RULE")));
        def.setOnUpdate(MysqlMetaData.trimToNull(rs.getString("UPDATE_RULE")));
        return def;
    }

    /**
     * MySQL 添加外键链式构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class MysqlForeignKeyCreateBuilder implements ForeignKeyCreateBuilder {

        /**
         * 所属外键元数据入口
         */
        private final MysqlMetaForeignKey metaFk;
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

        MysqlForeignKeyCreateBuilder(MysqlMetaForeignKey metaFk, String fkName) {
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
            String clause = MysqlMetaData.addForeignKeyClause(fkName, columnName, refTable, refColumn,
                    onDelete == null ? null : MysqlMetaData.referentialAction("删除规则", onDelete),
                    onUpdate == null ? null : MysqlMetaData.referentialAction("更新规则", onUpdate));
            String sql = "ALTER TABLE " + MysqlMetaData.quote(metaFk.tableName) + " " + clause;
            MysqlMetaData.execute(metaFk.engine, "创建外键 " + fkName, sql, new ArrayList<>());
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
        MysqlMetaForeignKey probe = new MysqlMetaForeignKey(metaData, engine);
        probe.tableName = table;
        return probe.get(fkName);
    }
}
