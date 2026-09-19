package com.chua.postgresql.support.meta;

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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL 元数据入口。
 * <p>
 * 提供表、视图、索引、外键、存储过程/函数、触发器的元数据操作能力；
 * 用户与权限、搜索索引在 PostgreSQL 侧尚未实现，保持显式
 * {@link UnsupportedOperationException}（不伪造能力）。
 * </p>
 * <p>
 * 本类同时作为 PostgreSQL 元数据族（{@code PostgresqlMeta*}）的公共 JDBC 门面，
 * 统一负责：连接获取、{@link PreparedStatement} 参数绑定、标识符引用、异常显式抛出。
 * 所有字典查询的模式名（schema）都以绑定参数下发，参数为 {@code null} 时由 SQL 侧的
 * {@code current_schema()} 取当前会话默认模式，杜绝字符串拼接带来的注入面。
 * </p>
 * <p>
 * 命名约定：PostgreSQL 标识符大小写敏感，未加引号的标识符会被折叠为小写。
 * 本实现一律使用双引号引用标识符，因此调用方传入的名字按"原样精确匹配"处理；
 * 需要小写折叠语义请自行传小写名字。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("postgresql")
public class PostgresqlMetaData extends JdbcMetaData {

    /**
     * PostgreSQL 双引号引用符。
     */
    private static final char QUOTE = '"';

    /**
     * 函数/过程体美元引用标签，包内统一使用同一个标签便于冲突校验。
     */
    static final String DOLLAR_TAG = "$body$";

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
     * PostgreSQL 元数据入口构造方法。
     *
     * @param engine 引擎实例
     */
    public PostgresqlMetaData(Engine engine) {
        super(engine);
    }

    @Override
    public MetaTable table() {
        return new PostgresqlMetaTable(this, engine);
    }

    @Override
    public MetaTable table(String tableName) {
        return new PostgresqlMetaTable(this, engine, tableName);
    }

    @Override
    public MetaView view() {
        return new PostgresqlMetaView(this, engine);
    }

    @Override
    public MetaView view(String viewName) {
        return new PostgresqlMetaView(this, engine, viewName);
    }

    @Override
    public MetaIndex index() {
        return new PostgresqlMetaIndex(this, engine);
    }

    @Override
    public MetaIndex index(String indexName) {
        return new PostgresqlMetaIndex(this, engine, indexName);
    }

    @Override
    public MetaTrigger trigger() {
        return new PostgresqlMetaTrigger(this, engine);
    }

    @Override
    public MetaTrigger trigger(String triggerName) {
        return new PostgresqlMetaTrigger(this, engine, triggerName);
    }

    @Override
    public MetaProcedure procedure() {
        return new PostgresqlMetaProcedure(this, engine);
    }

    @Override
    public MetaProcedure procedure(String procedureName) {
        return new PostgresqlMetaProcedure(this, engine, procedureName);
    }

    @Override
    public MetaForeignKey fk() {
        return new PostgresqlMetaForeignKey(this, engine);
    }

    @Override
    public MetaForeignKey fk(String fkName) {
        return new PostgresqlMetaForeignKey(this, engine, fkName);
    }

    @Override
    public MetaUser user() {
        throw new UnsupportedOperationException("PostgreSQL 用户管理暂不支持");
    }

    @Override
    public MetaPermission permission() {
        throw new UnsupportedOperationException("PostgreSQL 权限管理暂不支持");
    }

    @Override
    public MetaSearch search() {
        throw new UnsupportedOperationException(
                "PostgreSQL 没有独立的搜索索引对象：全文检索要先建 tsvector 生成列"
                        + "（ALTER TABLE t ADD COLUMN fts tsvector GENERATED ALWAYS AS (to_tsvector('simple', body)) STORED）"
                        + "再在该列上建 GIN 索引，SearchIndexDef 无法表达这一前置，故本模块未提供 search() 实现；"
                        + "需要时请实现 com.chua.common.support.lang.datasource.meta.SearchEngine"
                        + " 并注册到 META-INF/extensions 下");
    }

    @Override
    public MetaSearch search(String indexName) {
        return search();
    }

    // ==================== 元数据族公共 JDBC 门面 ====================

    /**
     * 解析当前上下文的模式名。
     * <p>
     * PostgreSQL 的 catalog 是数据库名，跨库需要另建连接，无法在 SQL 内过滤，
     * 故这里只取 {@code schema}；为 {@code null} 时由 SQL 侧 {@code current_schema()} 兜底。
     * </p>
     *
     * @param metaData 元数据入口
     * @return 模式名，可能为 {@code null}
     */
    static String resolveSchema(AbstractMetaData metaData) {
        return metaData == null ? null : metaData.getSchema();
    }

    /**
     * 获取默认数据源。
     *
     * @param metaData 元数据入口
     * @return JDBC 数据源
     * @throws IllegalStateException 默认数据源未配置或类型不支持 JDBC
     */
    static DataSource dataSource(AbstractMetaData metaData) {
        Engine engine = metaData.getEngine();
        if (engine != null) {
            EngineDataSource<?> eds = engine.getDataSource(engine.getDefaultDataSourceName());
            if (eds != null && eds.getSource() instanceof DataSource ds) {
                return ds;
            }
        }
        if (metaData instanceof PostgresqlMetaData postgresqlMetaData) {
            return postgresqlMetaData.getDataSource();
        }
        throw new IllegalStateException("默认数据源未配置，无法读取 PostgreSQL 元数据");
    }

    /**
     * 获取默认数据源的 JDBC 连接。
     *
     * @param metaData 元数据入口
     * @return JDBC 连接，调用方负责关闭
     * @throws SQLException          获取连接失败
     * @throws IllegalStateException 默认数据源未配置
     */
    static Connection connection(AbstractMetaData metaData) throws SQLException {
        return dataSource(metaData).getConnection();
    }

    /**
     * 引用 PostgreSQL 标识符。
     * <p>内部双引号按厂商规则加倍转义，拒绝控制字符，避免标识符提前闭合。</p>
     *
     * @param name 标识符
     * @return 引用后的标识符
     */
    static String quote(String name) {
        return SqlName.escape(name, QUOTE, "标识符");
    }

    /**
     * 校验 DDL 片段（列类型、访问方法名等无法参数化的语法片段）。
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
     * 校验 SQL 字符串字面量内容（注释等），拒绝换行与分号之外的越界语句。
     *
     * @param element 用途描述
     * @param value   原始值
     * @return 转义后的字面量（不含外层引号）
     */
    static String literal(String element, String value) {
        if (value == null) {
            throw new IllegalArgumentException(element + " 不能为 null");
        }
        return escapeSql(value);
    }

    /**
     * 执行元数据查询。
     *
     * @param metaData 元数据入口
     * @param context  业务上下文，用于异常信息定位
     * @param sql      预编译 SQL
     * @param args     绑定参数，允许元素为 {@code null}
     * @param mapper   行映射器
     * @param <T>      行类型
     * @return 结果列表，永不为 {@code null}
     * @throws IllegalStateException 查询失败（带 cause 与 SQL 上下文）
     */
    static <T> List<T> query(AbstractMetaData metaData, String context, String sql, List<?> args,
                             RowMapper<T> mapper) {
        try (Connection conn = connection(metaData);
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
     * @param metaData 元数据入口
     * @param context  业务上下文
     * @param sql      预编译 SQL
     * @param args     绑定参数
     * @param mapper   行映射器
     * @param <T>      行类型
     * @return 首行结果，无数据时返回 {@code null}
     * @throws IllegalStateException 查询失败
     */
    static <T> T queryOne(AbstractMetaData metaData, String context, String sql, List<?> args, RowMapper<T> mapper) {
        List<T> rows = query(metaData, context, sql, args, mapper);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 执行 DDL / CALL 等语句。
     *
     * @param metaData 元数据入口
     * @param context  业务上下文
     * @param sql      预编译 SQL
     * @param args     绑定参数，可为 {@code null} 或空集合
     * @return 执行成功返回 {@code true}
     * @throws IllegalStateException 执行失败
     */
    static boolean execute(AbstractMetaData metaData, String context, String sql, List<?> args) {
        try (Connection conn = connection(metaData);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            ps.execute();
            return true;
        } catch (SQLException | IllegalStateException e) {
            throw describe(e, context, sql, args);
        }
    }

    /**
     * 执行 {@code CALL} / {@code SELECT} 并收集首个结果集。
     *
     * @param metaData 元数据入口
     * @param context  业务上下文
     * @param sql      语句文本
     * @param args     绑定参数
     * @param callable 是否按 JDBC 调用转义语法执行
     * @return 结果行列表
     * @throws IllegalStateException 执行失败
     */
    static List<Map<String, Object>> call(AbstractMetaData metaData, String context, String sql, List<?> args,
                                         boolean callable) {
        try (Connection conn = connection(metaData);
             PreparedStatement ps = callable ? conn.prepareCall(sql) : conn.prepareStatement(sql)) {
            bind(ps, args);
            List<Map<String, Object>> rows = new ArrayList<>();
            if (ps.execute()) {
                try (ResultSet rs = ps.getResultSet()) {
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
     * 把结果集当前行逐列读为 {@code 列名 -> 值} 的有序 Map。
     *
     * @param rs 结果集，游标已定位到目标行
     * @return 行数据
     * @throws SQLException 读取失败
     */
    private static Map<String, Object> rowOf(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int columns = md.getColumnCount();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= columns; i++) {
            String label = md.getColumnLabel(i);
            row.put(label == null ? ("COL_" + i) : label, rs.getObject(i));
        }
        return row;
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
        StringBuilder msg = new StringBuilder("PostgreSQL 元数据操作失败[").append(context).append("]: ");
        if (args != null && !args.isEmpty()) {
            msg.append(Arrays.toString(args.toArray())).append(" | ");
        }
        msg.append(sql);
        return new IllegalStateException(msg.toString(), cause);
    }

    /**
     * 生成 PostgreSQL 的 {@code ADD CONSTRAINT ... FOREIGN KEY} 子句。
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
     * SQL 字符串字面量转义（单引号加倍，符合 PostgreSQL 标准）。
     *
     * @param value 原始值
     * @return 转义后的值，{@code null} 归一为空串
     */
    static String escapeSql(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    /**
     * 用美元引用包裹函数体，避免单引号/双引号转义歧义。
     *
     * @param element 用途描述，用于异常信息定位
     * @param content 待包裹的内容
     * @return {@code $body$ ... $body$} 形式的引用体
     * @throws IllegalArgumentException 内容为空或包含同一标签
     */
    static String dollarQuoted(String element, String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException(element + " 不能为空");
        }
        String trimmed = content.trim();
        if (trimmed.contains(DOLLAR_TAG)) {
            throw new IllegalArgumentException(element + " 不能包含美元引用标签: " + DOLLAR_TAG);
        }
        return DOLLAR_TAG + "\n" + trimmed + "\n" + DOLLAR_TAG;
    }

    /**
     * 把裸语句序列包装成 {@code plpgsql} 块（{@code BEGIN ... END}，每条语句补分号）。
     *
     * @param raw 原始语句体
     * @return 可直接放入 {@code plpgsql} 函数体的块
     */
    static String wrapPlpgsql(String raw) {
        StringBuilder sb = new StringBuilder("BEGIN\n");
        for (String statement : raw.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                sb.append("  ").append(trimmed.replace("\n", "\n  ")).append(";\n");
            }
        }
        sb.append("END;");
        return sb.toString();
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
