package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.meta.MetaForeignKey;
import com.chua.common.support.lang.datasource.meta.MetaIndex;
import com.chua.common.support.lang.datasource.meta.MetaPermission;
import com.chua.common.support.lang.datasource.meta.MetaProcedure;
import com.chua.common.support.lang.datasource.meta.MetaSearch;
import com.chua.common.support.lang.datasource.meta.MetaTable;
import com.chua.common.support.lang.datasource.meta.MetaTrigger;
import com.chua.common.support.lang.datasource.meta.MetaUser;
import com.chua.common.support.lang.datasource.meta.MetaView;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.JdbcMetaData;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MySQL 元数据入口。
 * <p>
 * 继承自 {@link JdbcMetaData}，提供表、视图、索引、外键、存储过程、触发器的元数据操作能力，
 * 用户与权限能力由 {@link MysqlMetaUser} / {@link MysqlMetaPermission} 提供。
 * </p>
 * <p>
 * 本类同时作为 MySQL 元数据族（{@code MysqlMeta*}）的公共 JDBC 门面，
 * 统一负责：连接获取、{@link PreparedStatement} 参数绑定、标识符引用、异常显式抛出。
 * 所有 {@code information_schema} 查询的库名（schema）都以绑定参数下发，
 * 参数为 {@code null} 时由 SQL 侧的 {@code DATABASE()} 取当前会话默认库，杜绝字符串拼接带来的注入面。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("mysql")
public class MysqlMetaData extends JdbcMetaData {

    /**
     * MySQL 反引号引用符。
     */
    private static final char QUOTE = '`';

    /**
     * 结果集行映射器。
     *
     * @param <T> 目标类型
     */
    @FunctionalInterface
    interface RowMapper<T> {

        /**
         * 将结果集当前行映射为目标对象。
         *
         * @param rs 结果集，游标已定位到目标行
         * @return 映射结果
         * @throws SQLException 读取列失败
         */
        T map(ResultSet rs) throws SQLException;
    }

    /**
     * MySQL 元数据入口构造方法。
     *
     * @param engine 引擎实例
     */
    public MysqlMetaData(Engine engine) {
        super(engine);
    }

    @Override
    public MetaTable table() {
        return new MysqlMetaTable(this, engine);
    }

    @Override
    public MetaTable table(String tableName) {
        return new MysqlMetaTable(this, engine, tableName);
    }

    @Override
    public MetaView view() {
        return new MysqlMetaView(this, engine);
    }

    @Override
    public MetaView view(String viewName) {
        return new MysqlMetaView(this, engine, viewName);
    }

    @Override
    public MetaIndex index() {
        return new MysqlMetaIndex(this, engine);
    }

    @Override
    public MetaIndex index(String indexName) {
        return new MysqlMetaIndex(this, engine, indexName);
    }

    @Override
    public MetaTrigger trigger() {
        return new MysqlMetaTrigger(this, engine);
    }

    @Override
    public MetaTrigger trigger(String triggerName) {
        return new MysqlMetaTrigger(this, engine, triggerName);
    }

    @Override
    public MetaProcedure procedure() {
        return new MysqlMetaProcedure(this, engine);
    }

    @Override
    public MetaProcedure procedure(String procedureName) {
        return new MysqlMetaProcedure(this, engine, procedureName);
    }

    @Override
    public MetaForeignKey fk() {
        return new MysqlMetaForeignKey(this, engine);
    }

    @Override
    public MetaForeignKey fk(String fkName) {
        return new MysqlMetaForeignKey(this, engine, fkName);
    }

    @Override
    public MetaUser user() {
        return new MysqlMetaUser(getDataSource());
    }

    @Override
    public MetaPermission permission() {
        return new MysqlMetaPermission(getDataSource());
    }

    @Override
    public MetaSearch search() {
        return new MysqlMetaSearch(this, engine, getDataSource());
    }

    @Override
    public MetaSearch search(String indexName) {
        return new MysqlMetaSearch(this, engine, getDataSource());
    }

    // ==================== 元数据族公共 JDBC 门面 ====================

    /**
     * 解析当前上下文的库名（MySQL 的 schema 即 database）。
     * <p>优先取 {@code schema}，其次取 {@code catalog}（MySQL 使用者常把库名写在 catalog 上），
     * 两者都为 {@code null} 时返回 {@code null}，由 SQL 侧的 {@code DATABASE()} 兜底。</p>
     *
     * @param metaData 元数据入口
     * @return 库名，可能为 {@code null}
     */
    static String resolveSchema(AbstractMetaData metaData) {
        if (metaData == null) {
            return null;
        }
        return metaData.getSchema() != null ? metaData.getSchema() : metaData.getCatalog();
    }

    /**
     * 获取默认数据源的 JDBC 连接。
     *
     * @param engine 引擎实例
     * @return JDBC 连接，调用方负责关闭
     * @throws SQLException            获取连接失败
     * @throws IllegalStateException   默认数据源未配置或类型不支持 JDBC
     */
    static Connection connection(Engine engine) throws SQLException {
        EngineDataSource<?> eds = engine.getDataSource(engine.getDefaultDataSourceName());
        if (eds == null) {
            throw new IllegalStateException("默认数据源未配置，无法读取 MySQL 元数据");
        }
        Object source = eds.getSource();
        if (source instanceof DataSource ds) {
            return ds.getConnection();
        }
        throw new IllegalStateException("数据源类型不支持 JDBC 连接获取: "
                + (source == null ? "null" : source.getClass().getName()));
    }

    /**
     * 引用 MySQL 标识符。
     * <p>内部出现的反引号按厂商规则加倍转义，拒绝控制字符，避免标识符提前闭合。</p>
     *
     * @param name 标识符
     * @return 引用后的标识符
     */
    static String quote(String name) {
        return SqlName.escape(name, '`', "标识符");
    }

    /**
     * 校验 DDL 片段（列类型、字符集、引擎名等无法参数化的语法片段）。
     * <p>仅拒绝语句分隔符与注释符，避免把额外语句塞进 DDL。</p>
     *
     * @param element 片段用途描述
     * @param value   片段内容
     * @return 原始片段
     */
    static String checkDdlFragment(String element, String value) {
        if (value == null) {
            throw new IllegalArgumentException(element + " 不能为 null");
        }
        if (value.indexOf(';') >= 0 || value.indexOf("--") >= 0 || value.indexOf("/*") >= 0
                || value.contains("*/") || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(element + " 含非法字符: " + value);
        }
        return value;
    }

    /**
     * 执行元数据查询。
     *
     * @param engine  引擎实例
     * @param context 业务上下文，用于异常信息定位
     * @param sql     预编译 SQL
     * @param args    绑定参数，允许元素为 {@code null}
     * @param mapper  行映射器
     * @param <T>     行类型
     * @return 结果列表，永不为 {@code null}
     * @throws IllegalStateException 查询失败（带 cause 与 SQL 上下文）
     */
    static <T> List<T> query(Engine engine, String context, String sql, List<?> args, RowMapper<T> mapper) {
        try (Connection conn = connection(engine);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        } catch (SQLException | IllegalStateException e) {
            throw describe(e, context, sql, args);
        }
    }

    /**
     * 执行元数据查询并取首行。
     *
     * @param engine  引擎实例
     * @param context 业务上下文
     * @param sql     预编译 SQL
     * @param args    绑定参数
     * @param mapper  行映射器
     * @param <T>     行类型
     * @return 首行结果，无数据时返回 {@code null}
     * @throws IllegalStateException 查询失败
     */
    static <T> T queryOne(Engine engine, String context, String sql, List<?> args, RowMapper<T> mapper) {
        List<T> rows = query(engine, context, sql, args, mapper);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 执行 DDL / CALL 等更新语句。
     *
     * @param engine  引擎实例
     * @param context 业务上下文
     * @param sql     预编译 SQL
     * @param args    绑定参数，可为 {@code null} 或空集合
     * @return 执行成功返回 {@code true}
     * @throws IllegalStateException 执行失败
     */
    static boolean execute(Engine engine, String context, String sql, List<?> args) {
        try (Connection conn = connection(engine);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            ps.execute();
            return true;
        } catch (SQLException | IllegalStateException e) {
            throw describe(e, context, sql, args);
        }
    }

    /**
     * 逐列读取结果集为 {@code 列名 -> 值} 的有序 Map 列表。
     *
     * @param engine  引擎实例
     * @param context 业务上下文
     * @param sql     预编译 SQL
     * @param args    绑定参数
     * @return 行列表
     * @throws IllegalStateException 查询失败
     */
    static List<java.util.Map<String, Object>> queryRows(Engine engine, String context, String sql, List<?> args) {
        return query(engine, context, sql, args, MysqlMetaData::rowOf);
    }

    /**
     * 执行 {@code CALL} 语句并收集首个结果集。
     *
     * @param engine  引擎实例
     * @param context 业务上下文
     * @param sql     JDBC 调用语句，形如 {@code {call `db`.`p`(?, ?)}}
     * @param args    绑定参数
     * @return 结果行列表，过程未返回结果集时为空列表
     * @throws IllegalStateException 调用失败
     */
    static List<java.util.Map<String, Object>> callProcedure(Engine engine, String context, String sql,
                                                             List<?> args) {
        try (Connection conn = connection(engine);
             java.sql.CallableStatement cs = conn.prepareCall(sql)) {
            bind(cs, args);
            List<java.util.Map<String, Object>> rows = new ArrayList<>();
            if (cs.execute()) {
                try (ResultSet rs = cs.getResultSet()) {
                    while (rs.next()) {
                        rows.add(rowOf(rs));
                    }
                }
            }
            return rows;
        } catch (SQLException | IllegalStateException e) {
            throw describe(e, context, sql, args);
        }
    }

    /**
     * 把结果集当前行逐列读为 {@code 列名 -> 值} 的有序 Map。
     *
     * @param rs 结果集，游标已定位到目标行
     * @return 行数据
     * @throws SQLException 读取失败
     */
    private static java.util.Map<String, Object> rowOf(ResultSet rs) throws SQLException {
        java.sql.ResultSetMetaData md = rs.getMetaData();
        int columns = md.getColumnCount();
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= columns; i++) {
            String label = md.getColumnLabel(i);
            row.put(label == null ? ("COL_" + i) : label, rs.getObject(i));
        }
        return row;
    }

    /**
     * 绑定参数，{@code null} 以 {@link Types#VARCHAR} 下发以便 {@code COALESCE(?, ...)} 正确推断类型。
     *
     * @param ps   预编译语句
     * @param args 参数列表
     * @throws SQLException 绑定失败
     */
    private static void bind(PreparedStatement ps, List<?> args) throws SQLException {
        if (args == null || args.isEmpty()) {
            return;
        }
        for (int i = 0; i < args.size(); i++) {
            Object arg = args.get(i);
            if (arg == null) {
                ps.setNull(i + 1, Types.VARCHAR);
            } else {
                ps.setObject(i + 1, arg);
            }
        }
    }

    /**
     * 组装带上下文的元数据异常，保证底层失败显式抛出而不是伪装成"没有元数据"。
     *
     * @param cause   原始异常
     * @param context 业务上下文
     * @param sql     SQL 文本
     * @param args    绑定参数
     * @return 运行时异常
     */
    private static IllegalStateException describe(Throwable cause, String context, String sql, List<?> args) {
        StringBuilder msg = new StringBuilder("MySQL 元数据操作失败[").append(context).append("]: ");
        if (args != null && !args.isEmpty()) {
            msg.append(Arrays.toString(args.toArray())).append(" | ");
        }
        msg.append(sql);
        return new IllegalStateException(msg.toString(), cause);
    }

    /**
     * 生成 MySQL 的 {@code ADD ... INDEX} 子句（建索引与改表共用）。
     * <p>语法顺序为 {@code 索引列) USING 算法 COMMENT '注释' INVISIBLE}；
     * {@code FULLTEXT} / {@code SPATIAL} 不能使用 {@code USING}。</p>
     *
     * @param quotedTable 已引用的表名
     * @param indexName   索引名
     * @param columns     索引列
     * @param unique      是否唯一索引
     * @param type        索引算法（BTREE / HASH / FULLTEXT / SPATIAL），可为 {@code null}
     * @param comment     索引注释，可为 {@code null}
     * @param visible     是否可见，{@code false} 输出 {@code INVISIBLE}
     * @return ALTER TABLE 的变更子句
     */
    static String addIndexClause(String quotedTable, String indexName, List<String> columns, boolean unique,
                                 String type, String comment, boolean visible) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalStateException("索引列不能为空");
        }
        if (quotedTable == null) {
            throw new IllegalStateException("未指定表名，请先调用 onTable(String)");
        }
        List<String> quoted = new ArrayList<>();
        for (String column : columns) {
            quoted.add(quote(column));
        }
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(quotedTable).append(" ADD ");
        String algorithm = type == null ? null : type.trim().toUpperCase();
        if ("FULLTEXT".equals(algorithm) || "SPATIAL".equals(algorithm)) {
            sb.append(algorithm).append(' ');
        }
        if (unique) {
            sb.append("UNIQUE ");
        }
        sb.append("INDEX ").append(quote(indexName)).append(" (").append(String.join(", ", quoted)).append(")");
        if (algorithm != null && !algorithm.isEmpty()
                && !"FULLTEXT".equals(algorithm) && !"SPATIAL".equals(algorithm)) {
            sb.append(" USING ").append(checkDdlFragment("索引类型", algorithm));
        }
        if (comment != null && !comment.isEmpty()) {
            sb.append(" COMMENT '").append(comment.replace("'", "''")).append("'");
        }
        if (!visible) {
            sb.append(" INVISIBLE");
        }
        return sb.toString();
    }

    /**
     * 生成 MySQL 的 {@code ADD CONSTRAINT ... FOREIGN KEY ...} 子句。
     *
     * @param fkName     外键名
     * @param columnName 当前表列名
     * @param refTable   引用表名
     * @param refColumn  引用列名
     * @param onDelete   删除规则，可为 {@code null}
     * @param onUpdate   更新规则，可为 {@code null}
     * @return 变更子句
     */
    static String addForeignKeyClause(String fkName, String columnName, String refTable, String refColumn,
                                      String onDelete, String onUpdate) {
        if (columnName == null || refTable == null || refColumn == null) {
            throw new IllegalStateException("外键必须指定列与引用表列，请先调用 column(String) 与 references(String, String)");
        }
        StringBuilder sb = new StringBuilder("ADD CONSTRAINT ").append(quote(fkName))
                .append(" FOREIGN KEY (").append(quote(columnName)).append(")")
                .append(" REFERENCES ").append(quote(refTable)).append(" (").append(quote(refColumn)).append(")");
        if (onDelete != null && !onDelete.isEmpty()) {
            sb.append(" ON DELETE ").append(referentialAction("删除规则", onDelete));
        }
        if (onUpdate != null && !onUpdate.isEmpty()) {
            sb.append(" ON UPDATE ").append(referentialAction("更新规则", onUpdate));
        }
        return sb.toString();
    }

    /**
     * 校验外键引用动作，只允许 SQL 标准取值。
     *
     * @param element 用途描述
     * @param action  动作值
     * @return 归一为大写后的动作
     */
    static String referentialAction(String element, String action) {
        String upper = action.trim().toUpperCase();
        switch (upper) {
            case "CASCADE", "SET NULL", "RESTRICT", "NO ACTION", "SET DEFAULT" -> {
                return upper;
            }
            default -> throw new IllegalArgumentException(element + " 非法: " + action);
        }
    }

    /**
     * 空白串归一为 {@code null}，避免把厂商返回的空注释当成有效信息。
     *
     * @param value 原始值
     * @return 归一后的值
     */
    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * SQL 字符串字面量转义（单引号加倍）。
     *
     * @param value 原始值
     * @return 转义后的值，{@code null} 归一为空串
     */
    static String escapeSql(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    /**
     * 参数列表快捷构造，允许 {@code null} 元素。
     *
     * @param args 参数
     * @return 可变参数列表
     */
    static List<Object> args(Object... args) {
        return args == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(args));
    }
}
