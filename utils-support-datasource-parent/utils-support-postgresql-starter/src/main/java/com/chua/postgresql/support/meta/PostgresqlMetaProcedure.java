package com.chua.postgresql.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.ProcedureCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.ProcedureDef;
import com.chua.common.support.lang.datasource.meta.model.ProcedureParamDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaProcedure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL 存储过程 / 函数元数据操作。
 * <p>
 * 读取路径为 {@code pg_proc}（主档：{@code prokind} 区分过程与函数、{@code prosrc} 取定义体、
 * {@code prosecdef} 取安全类型、{@code obj_description} 取注释、{@code format_type(prorettype)} 取返回类型）
 * 配合 {@code unnest(COALESCE(proallargtypes, proargtypes::oid[])) WITH ORDINALITY} 逐参数展开
 * （{@code proargmodes} 给出 IN / OUT / INOUT / VARIADIC / TABLE 方向，{@code proargnames} 给出参数名）。
 * 模式名与名字均为绑定参数，模式名为 {@code null} 时由 SQL 侧 {@code current_schema()} 兜底。
 * </p>
 * <p>
 * 能力边界：
 * <ul>
 *   <li>{@link ProcedureDef#getStatus()}（VALID/INVALID）在 PostgreSQL 中不存在，保持 {@code null}。</li>
 *   <li>{@link ProcedureDef} 没有"过程/函数"字段，故 {@code list()} 同时返回两者；
 *       函数额外带 {@code returnType}（集合返回带 {@code []} 后缀），过程为 {@code null}。</li>
 *   <li>同名重载在 {@link ProcedureDef} 中没有签名承载位，{@code get(String)} 返回按名字排序的首个重载。</li>
 *   <li>创建契约 {@link ProcedureCreateBuilder} 没有 {@code RETURNS} 入口，
 *       因此 {@code create(String)} 只能产出 {@code CREATE PROCEDURE}；建函数请走引擎的语句执行入口。</li>
 *   <li>PostgreSQL 没有 {@code CREATE OR REPLACE PROCEDURE}（14 以下），{@code orReplace()}
 *       统一实现为"先 {@code DROP ... IF EXISTS} 再 {@code CREATE}"，版本无关。</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PostgresqlMetaProcedure extends AbstractMetaProcedure {

    /**
     * 过程/函数主档查询。
     */
    private static final String ROUTINE_SQL =
            "SELECT current_database() AS routine_catalog, n.nspname AS routine_schema,"
                    + " p.proname AS routine_name,"
                    + " CASE p.prokind WHEN 'f' THEN 'FUNCTION' ELSE 'PROCEDURE' END AS routine_type,"
                    + " l.lanname AS routine_language,"
                    + " CASE WHEN p.proretset THEN pg_catalog.format_type(p.prorettype, NULL) || '[]'"
                    + " ELSE pg_catalog.format_type(p.prorettype, NULL) END AS return_type,"
                    + " p.prosrc AS routine_body,"
                    + " pg_catalog.obj_description(p.oid, 'pg_proc') AS routine_comment,"
                    + " CASE WHEN p.prosecdef THEN 'DEFINER' ELSE 'INVOKER' END AS security_type"
                    + " FROM pg_catalog.pg_proc p"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace"
                    + " JOIN pg_catalog.pg_language l ON l.oid = p.prolang"
                    + " WHERE n.nspname = COALESCE(?, current_schema()) AND p.prokind IN ('p', 'f')";

    /**
     * 参数查询：{@code proargmodes} / {@code proargnames} 按下标对齐取值。
     */
    private static final String PARAM_SQL =
            "SELECT current_database() AS specific_catalog, n.nspname AS specific_schema,"
                    + " p.proname AS specific_name,"
                    + " CASE p.prokind WHEN 'f' THEN 'FUNCTION' ELSE 'PROCEDURE' END AS routine_type,"
                    + " t.ord::int AS ordinal_position,"
                    + " CASE COALESCE(p.proargmodes[t.ord], 'i') WHEN 'i' THEN 'IN' WHEN 'o' THEN 'OUT'"
                    + " WHEN 'b' THEN 'INOUT' WHEN 'v' THEN 'VARIADIC' WHEN 't' THEN 'TABLE' ELSE 'IN' END"
                    + " AS parameter_mode,"
                    + " COALESCE(p.proargnames[t.ord], '') AS parameter_name,"
                    + " pg_catalog.format_type(t.typ, NULL) AS dtd_identifier"
                    + " FROM pg_catalog.pg_proc p"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace"
                    + " JOIN LATERAL unnest(COALESCE(p.proallargtypes, p.proargtypes::oid[]))"
                    + " WITH ORDINALITY AS t(typ, ord) ON true"
                    + " WHERE n.nspname = COALESCE(?, current_schema()) AND p.prokind IN ('p', 'f')";

    /**
     * 过程/函数类别查询，供 {@code drop} 与 {@code call} 选择正确语法。
     */
    private static final String KIND_SQL =
            "SELECT CASE p.prokind WHEN 'f' THEN 'FUNCTION' ELSE 'PROCEDURE' END AS routine_type"
                    + " FROM pg_catalog.pg_proc p"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace"
                    + " WHERE n.nspname = COALESCE(?, current_schema()) AND p.proname = ?"
                    + " ORDER BY p.oid LIMIT 1";

    /**
     * 构造方法（无过程名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected PostgresqlMetaProcedure(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
     * 构造方法（带过程名上下文）。
     *
     * @param metaData      元数据入口
     * @param engine        引擎实例
     * @param procedureName 存储过程名
     */
    protected PostgresqlMetaProcedure(AbstractMetaData metaData, Engine engine, String procedureName) {
        super(metaData, engine, procedureName);
    }

    /**
     * 列出当前模式下的全部存储过程与函数。
     *
     * @return 过程定义列表（含参数）
     * @throws IllegalStateException 查询失败
     */
    @Override
    public List<ProcedureDef> list() {
        return readRoutines(null);
    }

    /**
     * 获取指定存储过程/函数的定义。
     *
     * @param procedureName 存储过程名，为 {@code null} 时使用构造期上下文
     * @return 过程定义，不存在时返回 {@code null}
     * @throws IllegalStateException 未指定过程名或查询失败
     */
    @Override
    public ProcedureDef get(String procedureName) {
        String target = procedureName != null ? procedureName : this.procedureName;
        if (target == null) {
            throw new IllegalStateException("未指定存储过程名");
        }
        List<ProcedureDef> defs = readRoutines(target);
        return defs.isEmpty() ? null : defs.get(0);
    }

    @Override
    public ProcedureCreateBuilder create(String procedureName) {
        return new PostgresProcedureCreateBuilder(this, procedureName);
    }

    /**
     * 删除存储过程或函数（按 {@code prokind} 选择 {@code DROP PROCEDURE} 或 {@code DROP FUNCTION}）。
     *
     * @param procedureName 存储过程名
     * @return 是否成功
     * @throws IllegalStateException 未指定过程名或执行失败
     */
    @Override
    public boolean drop(String procedureName) {
        if (procedureName == null) {
            throw new IllegalStateException("未指定存储过程名");
        }
        String kind = readKind(procedureName);
        String sql = "DROP " + (kind == null ? "PROCEDURE" : kind) + " IF EXISTS "
                + PostgresqlMetaData.quote(procedureName);
        return PostgresqlMetaData.execute(metaData, "删除" + (kind == null ? "过程" : kind) + " " + procedureName,
                sql, PostgresqlMetaData.args());
    }

    @Override
    public List<Map<String, Object>> call(Object... args) {
        return call(procedureName, args);
    }

    /**
     * 调用存储过程或函数。
     * <p>
     * 过程用 JDBC 调用转义 {@code {call name(?, ?)}}，函数用 {@code SELECT name(?, ?)} 取结果集，
     * 分支依据来自 {@code pg_proc.prokind} 而非猜测。
     * </p>
     *
     * @param procedureName 存储过程名
     * @param args          参数值，允许 {@code null} 元素
     * @return 结果行列表，无结果集时为空列表
     * @throws IllegalStateException 未指定过程名或调用失败
     */
    @Override
    public List<Map<String, Object>> call(String procedureName, Object... args) {
        if (procedureName == null) {
            throw new IllegalStateException("未指定存储过程名");
        }
        List<Object> values = PostgresqlMetaData.args(args);
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        String kind = readKind(procedureName);
        if (kind == null) {
            throw new IllegalStateException("过程或函数不存在: " + procedureName
                    + "（模式过滤=" + (PostgresqlMetaData.resolveSchema(metaData) == null
                    ? "current_schema()" : PostgresqlMetaData.resolveSchema(metaData)) + "）");
        }
        boolean callable = "PROCEDURE".equals(kind);
        String sql = callable
                ? "{call " + PostgresqlMetaData.quote(procedureName) + "(" + placeholders + ")}"
                : "SELECT " + PostgresqlMetaData.quote(procedureName) + "(" + placeholders + ")";
        return PostgresqlMetaData.call(metaData, "调用" + kind + " " + procedureName, sql, values, callable);
    }

    /**
     * 读取 {@code prokind} 派生的类别。
     *
     * @param name 过程名
     * @return {@code PROCEDURE} / {@code FUNCTION}，不存在时 {@code null}
     * @throws IllegalStateException 查询失败
     */
    private String readKind(String name) {
        return PostgresqlMetaData.queryOne(metaData, "定位过程 " + name, KIND_SQL,
                PostgresqlMetaData.args(PostgresqlMetaData.resolveSchema(metaData), name),
                rs -> PostgresqlMetaData.trimToNull(rs.getString("routine_type")));
    }

    /**
     * 读取过程定义并归并参数。
     *
     * @param name 过程名过滤，{@code null} 表示当前模式全部过程
     * @return 过程定义列表
     * @throws IllegalStateException 查询失败
     */
    private List<ProcedureDef> readRoutines(String name) {
        String schema = PostgresqlMetaData.resolveSchema(metaData);
        String suffix = name == null ? "" : " " + name;
        String routineContext = "查询存储过程" + suffix;
        String routineSql = name == null ? ROUTINE_SQL + " ORDER BY p.proname, p.oid"
                : ROUTINE_SQL + " AND p.proname = ? ORDER BY p.proname, p.oid";
        List<RoutineRow> rows = PostgresqlMetaData.query(metaData, routineContext, routineSql,
                name == null ? PostgresqlMetaData.args(schema) : PostgresqlMetaData.args(schema, name),
                RoutineRow::read);
        List<ProcedureDef> defs = new ArrayList<>();
        if (rows.isEmpty()) {
            return defs;
        }
        String paramSql = name == null ? PARAM_SQL : PARAM_SQL + " AND p.proname = ?";
        List<ParamRow> params = PostgresqlMetaData.query(metaData, "查询存储过程参数" + suffix, paramSql,
                name == null ? PostgresqlMetaData.args(schema) : PostgresqlMetaData.args(schema, name),
                ParamRow::read);
        Map<String, List<ProcedureParamDef>> grouped = new LinkedHashMap<>();
        for (ParamRow row : params) {
            ProcedureParamDef param = new ProcedureParamDef();
            param.setName(row.parameterName());
            param.setType(row.dtd());
            param.setDirection(row.mode());
            param.setPosition(row.ordinalPosition());
            grouped.computeIfAbsent(row.specificSchema() + "." + row.routineType() + "." + row.specificName(),
                    k -> new ArrayList<>()).add(param);
        }
        for (RoutineRow row : rows) {
            defs.add(row.toDef(grouped.getOrDefault(row.key(), new ArrayList<>())));
        }
        return defs;
    }

    /**
     * {@code pg_proc} 单行原始值。
     *
     * @author CH
     * @since 4.0.0
     */
    private record RoutineRow(String routineCatalog, String routineSchema, String routineName, String routineType,
                              String language, String returnType, String body, String comment, String securityType) {

        /**
         * 读取结果集当前行。
         *
         * @param rs 结果集
         * @return 行值
         * @throws SQLException 读取失败
         */
        static RoutineRow read(ResultSet rs) throws SQLException {
            String type = rs.getString("routine_type");
            String returnType = PostgresqlMetaData.trimToNull(rs.getString("return_type"));
            return new RoutineRow(PostgresqlMetaData.trimToNull(rs.getString("routine_catalog")),
                    rs.getString("routine_schema"), rs.getString("routine_name"), type,
                    PostgresqlMetaData.trimToNull(rs.getString("routine_language")),
                    "FUNCTION".equals(type) && !"void".equals(returnType) ? returnType : null,
                    rs.getString("routine_body"),
                    PostgresqlMetaData.trimToNull(rs.getString("routine_comment")),
                    PostgresqlMetaData.trimToNull(rs.getString("security_type")));
        }

        /**
         * 参数归并键。
         *
         * @return {@code 模式.类别.名字}
         */
        String key() {
            return routineSchema + "." + routineType + "." + routineName;
        }

        /**
         * 转成 {@link ProcedureDef}，逐列对应模型属性。
         *
         * @param params 参数列表
         * @return 过程定义
         */
        ProcedureDef toDef(List<ProcedureParamDef> params) {
            ProcedureDef def = new ProcedureDef();
            def.setName(routineName);
            def.setCatalog(routineCatalog);
            def.setSchema(routineSchema);
            def.setLanguage(language);
            def.setBody(body);
            def.setComment(comment);
            def.setSecurityType(securityType);
            def.setReturnType(returnType);
            def.setParams(params);
            // PostgreSQL 字典没有 VALID/INVALID 状态，保持 null
            return def;
        }
    }

    /**
     * {@code pg_proc} 参数展开后的单行原始值。
     *
     * @author CH
     * @since 4.0.0
     */
    private record ParamRow(String specificSchema, String specificName, String routineType, Integer ordinalPosition,
                            String mode, String parameterName, String dtd) {

        /**
         * 读取结果集当前行。
         *
         * @param rs 结果集
         * @return 行值
         * @throws SQLException 读取失败
         */
        static ParamRow read(ResultSet rs) throws SQLException {
            return new ParamRow(rs.getString("specific_schema"), rs.getString("specific_name"),
                    rs.getString("routine_type"), toInteger(rs, "ordinal_position"),
                    PostgresqlMetaData.trimToNull(rs.getString("parameter_mode")),
                    PostgresqlMetaData.trimToNull(rs.getString("parameter_name")),
                    PostgresqlMetaData.trimToNull(rs.getString("dtd_identifier")));
        }

        /**
         * 读取可空数值列。
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
    }

    /**
     * PostgreSQL 建存储过程链式构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class PostgresProcedureCreateBuilder implements ProcedureCreateBuilder {

        /**
         * 所属过程元数据入口
         */
        private final PostgresqlMetaProcedure metaProcedure;
        /**
         * 过程名
         */
        private final String procedureName;
        /**
         * 参数子句
         */
        private final List<String> params = new ArrayList<>();
        /**
         * 过程体
         */
        private final StringBuilder body = new StringBuilder();
        /**
         * 语言
         */
        private String language = "plpgsql";
        /**
         * 安全类型
         */
        private String securityType;
        /**
         * 注释
         */
        private String comment;
        /**
         * 是否先删后建
         */
        private boolean orReplace;

        PostgresProcedureCreateBuilder(PostgresqlMetaProcedure metaProcedure, String procedureName) {
            this.metaProcedure = metaProcedure;
            this.procedureName = procedureName;
        }

        @Override
        public ProcedureCreateBuilder in(String name, String type) {
            return param(name, type, "IN");
        }

        @Override
        public ProcedureCreateBuilder out(String name, String type) {
            return param(name, type, "OUT");
        }

        @Override
        public ProcedureCreateBuilder inout(String name, String type) {
            return param(name, type, "INOUT");
        }

        @Override
        public ProcedureCreateBuilder param(String name, String type, String direction) {
            String upper = direction == null ? "IN" : direction.trim().toUpperCase();
            if (!"IN".equals(upper) && !"OUT".equals(upper) && !"INOUT".equals(upper) && !"VARIADIC".equals(upper)) {
                throw new IllegalArgumentException(
                        "PostgreSQL 过程参数方向仅支持 IN / OUT / INOUT / VARIADIC，实际为: " + direction);
            }
            params.add(upper + " " + PostgresqlMetaData.quote(name) + " "
                    + PostgresqlMetaData.checkDdlFragment("参数类型", type));
            return this;
        }

        @Override
        public ProcedureCreateBuilder body(String body) {
            this.body.append(body);
            return this;
        }

        @Override
        public ProcedureCreateBuilder language(String language) {
            if (language != null && !language.trim().isEmpty()) {
                this.language = PostgresqlMetaData.checkDdlFragment("过程语言", language.trim().toLowerCase());
            }
            return this;
        }

        @Override
        public ProcedureCreateBuilder securityType(String securityType) {
            String upper = securityType == null ? null : securityType.trim().toUpperCase();
            if (upper != null && !"DEFINER".equals(upper) && !"INVOKER".equals(upper)) {
                throw new IllegalArgumentException(
                        "PostgreSQL 安全类型仅支持 DEFINER / INVOKER，实际为: " + securityType);
            }
            this.securityType = upper;
            return this;
        }

        @Override
        public ProcedureCreateBuilder comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        public ProcedureCreateBuilder orReplace() {
            this.orReplace = true;
            return this;
        }

        /**
         * 执行建过程语句，并以字典读回结果作为返回值。
         *
         * @return 落库后的过程定义
         * @throws IllegalStateException 过程体为空或执行失败
         */
        @Override
        public ProcedureDef execute() {
            String raw = body.toString().trim();
            if (raw.isEmpty()) {
                throw new IllegalStateException("存储过程体不能为空");
            }
            if (orReplace) {
                metaProcedure.drop(procedureName);
            }
            String content = "sql".equals(language) ? raw : PostgresqlMetaData.wrapPlpgsql(raw);
            StringBuilder sql = new StringBuilder("CREATE PROCEDURE ")
                    .append(PostgresqlMetaData.quote(procedureName)).append('(')
                    .append(String.join(", ", params)).append(")\n")
                    .append("SECURITY ").append("DEFINER".equals(securityType) ? "DEFINER" : "INVOKER").append('\n')
                    .append("LANGUAGE ").append(language).append(" AS ")
                    .append(PostgresqlMetaData.dollarQuoted("存储过程体", content));
            PostgresqlMetaData.execute(metaProcedure.metaData, "创建存储过程 " + procedureName, sql.toString(),
                    PostgresqlMetaData.args());
            if (comment != null && !comment.isEmpty()) {
                PostgresqlMetaData.execute(metaProcedure.metaData, "注释存储过程 " + procedureName,
                        "COMMENT ON PROCEDURE " + PostgresqlMetaData.quote(procedureName) + " IS '"
                                + PostgresqlMetaData.escapeSql(comment) + "'", PostgresqlMetaData.args());
            }
            return metaProcedure.get(procedureName);
        }
    }
}
