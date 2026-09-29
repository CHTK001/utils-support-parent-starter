package com.chua.mysql.support.meta;

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
 * MySQL 存储过程 / 函数元数据操作。
 * <p>
 * 读取路径为 {@code INFORMATION_SCHEMA.ROUTINES}（过程与函数的主档，含定义体、注释、安全类型）
 * 配合 {@code INFORMATION_SCHEMA.PARAMETERS}（逐参数模式、类型、序号），
 * 库名、过程名均为绑定参数，库名为 {@code null} 时由 SQL 侧 {@code DATABASE()} 收敛到当前会话默认库。
 * 不再使用 {@code SHOW PROCEDURE STATUS} / {@code SHOW CREATE PROCEDURE} 这类只能拼字符串的读取方式。
 * </p>
 * <p>
 * 参数按库一次性批量读取后在内存归并，避免逐个过程回查造成 N+1。
 * </p>
 * <p>
 * 能力边界：
 * <ul>
 *   <li>{@link ProcedureDef#getStatus()}（VALID/INVALID）在 MySQL 字典中不存在，保持 {@code null}，不伪造。</li>
 *   <li>MySQL 过程语言恒为 SQL（取自 {@code ROUTINE_BODY}），{@code language(String)} 传入其它语言显式拒绝。</li>
 *   <li>MySQL 8.0.29 之前没有 {@code CREATE OR REPLACE PROCEDURE}，
 *       {@code orReplace()} 统一实现为"先 {@code DROP IF EXISTS} 再 {@code CREATE}"，语义等价且不挑版本。</li>
 *   <li>{@link ProcedureDef} 没有区分"过程/函数"的字段，故 {@code list()} 同时返回 {@code PROCEDURE} 与
 *       {@code FUNCTION}（与接口注释一致），函数额外带 {@code returnType}，过程该值为 {@code null}。</li>
 *   <li>{@code call()} 只收集首个结果集：{@link ProcedureDef} 模型没有出参承载位，OUT/INOUT 不回填。</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MysqlMetaProcedure extends AbstractMetaProcedure {

    /**
     * 过程/函数主档查询。
     */
    private static final String ROUTINE_SQL =
            "SELECT r.ROUTINE_CATALOG, r.ROUTINE_SCHEMA, r.ROUTINE_NAME, r.ROUTINE_TYPE, r.DATA_TYPE,"
                    + " r.DTD_IDENTIFIER, r.ROUTINE_BODY, r.ROUTINE_DEFINITION, r.ROUTINE_COMMENT,"
                    + " r.SECURITY_TYPE"
                    + " FROM INFORMATION_SCHEMA.ROUTINES r"
                    + " WHERE r.ROUTINE_SCHEMA = COALESCE(?, DATABASE())";

    /**
     * 参数查询：{@code ORDINAL_POSITION > 0} 用于剔除函数的返回值占位行。
     */
    private static final String PARAM_SQL =
            "SELECT p.SPECIFIC_SCHEMA, p.SPECIFIC_NAME, p.ROUTINE_TYPE, p.ORDINAL_POSITION, p.PARAMETER_MODE,"
                    + " p.PARAMETER_NAME, p.DATA_TYPE, p.DTD_IDENTIFIER"
                    + " FROM INFORMATION_SCHEMA.PARAMETERS p"
                    + " WHERE p.SPECIFIC_SCHEMA = COALESCE(?, DATABASE()) AND p.ORDINAL_POSITION > 0";

    /**
     * 构造方法（无过程名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected MysqlMetaProcedure(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
     * 构造方法（带过程名上下文）。
     *
     * @param metaData      元数据入口
     * @param engine        引擎实例
     * @param procedureName 存储过程名
     */
    protected MysqlMetaProcedure(AbstractMetaData metaData, Engine engine, String procedureName) {
        super(metaData, engine, procedureName);
    }

    /**
     * 列出当前库下的全部存储过程与函数。
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
        return new MysqlProcedureCreateBuilder(this, procedureName);
    }

    /**
     * 删除存储过程。
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
        return MysqlMetaData.execute(engine, "删除存储过程 " + procedureName,
                "DROP PROCEDURE IF EXISTS " + qualified(procedureName), List.of());
    }

    @Override
    public List<Map<String, Object>> call(Object... args) {
        return call(procedureName, args);
    }

    /**
     * 调用存储过程，收集其返回的首个结果集。
     *
     * @param procedureName 存储过程名
     * @param args          IN 参数值，允许 {@code null} 元素
     * @return 结果行列表，过程未返回结果集时为空列表
     * @throws IllegalStateException 未指定过程名或调用失败
     */
    @Override
    public List<Map<String, Object>> call(String procedureName, Object... args) {
        if (procedureName == null) {
            throw new IllegalStateException("未指定存储过程名");
        }
        List<Object> values = MysqlMetaData.args(args);
        StringBuilder sql = new StringBuilder("{call ").append(qualified(procedureName)).append('(');
        for (int i = 0; i < values.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(")}");
        return MysqlMetaData.callProcedure(engine, "调用存储过程 " + procedureName, sql.toString(), values);
    }

    /**
     * 读取过程定义并归并参数。
     *
     * @param name 过程名过滤，{@code null} 表示当前库全部过程
     * @return 过程定义列表
     * @throws IllegalStateException 查询失败
     */
    private List<ProcedureDef> readRoutines(String name) {
        String schema = MysqlMetaData.resolveSchema(metaData);
        String suffix = name == null ? "" : " " + name;
        String routineContext = "查询存储过程" + suffix;
        String paramContext = "查询存储过程参数" + suffix;
        String routineSql = name == null ? ROUTINE_SQL + " ORDER BY r.ROUTINE_NAME, r.ROUTINE_TYPE"
                : ROUTINE_SQL + " AND r.ROUTINE_NAME = ? ORDER BY r.ROUTINE_NAME, r.ROUTINE_TYPE";
        List<RoutineRow> rows = MysqlMetaData.query(engine, routineContext, routineSql,
                name == null ? MysqlMetaData.args(schema) : MysqlMetaData.args(schema, name), RoutineRow::read);
        List<ProcedureDef> defs = new ArrayList<>();
        if (rows.isEmpty()) {
            return defs;
        }
        String paramSql = name == null ? PARAM_SQL : PARAM_SQL + " AND p.SPECIFIC_NAME = ?";
        List<ParamRow> params = MysqlMetaData.query(engine, paramContext, paramSql,
                name == null ? MysqlMetaData.args(schema) : MysqlMetaData.args(schema, name), ParamRow::read);
        Map<String, List<ProcedureParamDef>> grouped = new LinkedHashMap<>();
        for (ParamRow row : params) {
            ProcedureParamDef param = new ProcedureParamDef();
            param.setName(row.parameterName());
            param.setType(row.dtd() != null ? row.dtd() : row.dataType());
            param.setDirection(row.mode() == null ? "IN" : row.mode());
            param.setPosition(row.ordinalPosition());
            grouped.computeIfAbsent(row.routineType() + "." + row.specificName(), k -> new ArrayList<>()).add(param);
        }
        for (RoutineRow row : rows) {
            defs.add(row.toDef(grouped.getOrDefault(row.key(), new ArrayList<>())));
        }
        return defs;
    }

    /**
     * 生成带库名前缀的过程引用名；库名未指定时交给会话默认库。
     *
     * @param name 过程名
     * @return 引用后的过程名
     */
    private String qualified(String name) {
        String schema = MysqlMetaData.resolveSchema(metaData);
        return schema == null ? MysqlMetaData.quote(name)
                : MysqlMetaData.quote(schema) + "." + MysqlMetaData.quote(name);
    }

    /**
     * {@code ROUTINES} 单行原始值。
     *
     * <p>本 record 只做「字典列 → Java 值」的一比一搬运，不做类型归一，
     * 全部组件按 {@code MysqlMetaData.trimToNull(String)} 语义把空白串归一为
     * {@code null}（{@code routineSchema} / {@code routineName} / {@code routineType} 例外，
     * 它们是 {@code key()} 归并键的组成部分，必须原样保留）。
     * 无紧凑构造器，故所有组件都允许为 {@code null}，调用方
     * {@code toDef(List)} 对可空列显式做了回退处理。</p>
     *
     * @param routineCatalog     例程所属目录（catalog）名，MySQL 恒为 {@code def}；
     *                            取自 {@code ROUTINE_CATALOG}，空白归一为 {@code null}，
     *                            写入 {@code ProcedureDef.setCatalog}
     * @param routineSchema      例程所属库（schema）名，取自 {@code ROUTINE_SCHEMA}；
     *                            因 SQL 侧已用 {@code ROUTINE_SCHEMA = COALESCE(?, DATABASE())} 过滤，
     *                            实际恒非空，写入 {@code ProcedureDef.setSchema}
     * @param routineName        过程名或函数名，取自 {@code ROUTINE_NAME}，原样保留大小写，
     *                            写入 {@code ProcedureDef.setName}
     * @param routineType        例程类型，取自 {@code ROUTINE_TYPE}，取值 {@code PROCEDURE} 或
     *                            {@code FUNCTION}；参与 {@code key()} 归并，不允许为 {@code null}
     * @param dataType           返回值的数据类型名（不含长度/精度），取自 {@code DATA_TYPE}，
     *                            如 {@code int}、{@code decimal}；仅函数有值，过程为 {@code null}
     * @param dtd                返回值的完整数据类型描述，取自 {@code DTD_IDENTIFIER}，
     *                            如 {@code int(11)}、{@code decimal(10,2)}、{@code varchar(64)}；
     *                            {@code toDef(List)} 优先用它填 {@code returnType}，缺失时回退 {@link #dataType}
     * @param routineBody        例程体所用语言，取自 {@code ROUTINE_BODY}，MySQL 恒为 {@code SQL}；
     *                            写入 {@code ProcedureDef.setLanguage}
     * @param routineDefinition  例程体全文，取自 {@code ROUTINE_DEFINITION}，即 {@code CREATE} 时的
     *                            {@code BEGIN ... END} 内部语句；不做去空白处理以保留原始缩进，
     *                            写入 {@code ProcedureDef.setBody}
     * @param routineComment     例程注释文本，取自 {@code ROUTINE_COMMENT}（{@code COMMENT} 子句），
     *                            空白归一为 {@code null}，写入 {@code ProcedureDef.setComment}
     * @param securityType       SQL 安全类型，取自 {@code SECURITY_TYPE}，取值
     *                            {@code DEFINER} 或 {@code INVOKER}，写入 {@code ProcedureDef.setSecurityType}
     * @author CH
     * @since 4.0.0
     */
    private record RoutineRow(String routineCatalog, String routineSchema, String routineName, String routineType,
                              String dataType, String dtd, String routineBody, String routineDefinition,
                              String routineComment, String securityType) {

        /**
         * 读取结果集当前行。
         *
         * @param rs 结果集
         * @return 行值
         * @throws SQLException 读取失败
         */
        static RoutineRow read(ResultSet rs) throws SQLException {
            return new RoutineRow(MysqlMetaData.trimToNull(rs.getString("ROUTINE_CATALOG")),
                    rs.getString("ROUTINE_SCHEMA"), rs.getString("ROUTINE_NAME"),
                    rs.getString("ROUTINE_TYPE"), MysqlMetaData.trimToNull(rs.getString("DATA_TYPE")),
                    MysqlMetaData.trimToNull(rs.getString("DTD_IDENTIFIER")),
                    MysqlMetaData.trimToNull(rs.getString("ROUTINE_BODY")), rs.getString("ROUTINE_DEFINITION"),
                    MysqlMetaData.trimToNull(rs.getString("ROUTINE_COMMENT")),
                    MysqlMetaData.trimToNull(rs.getString("SECURITY_TYPE")));
        }

        /**
         * 参数归并键。
         *
         * @return {@code ROUTINE_TYPE.ROUTINE_NAME}
         */
        String key() {
            return routineType + "." + routineName;
        }

        /**
         * 转成 {@link ProcedureDef}，逐列对应模型属性。
         *
         * @param params 该过程的参数列表
         * @return 过程定义
         */
        ProcedureDef toDef(List<ProcedureParamDef> params) {
            ProcedureDef def = new ProcedureDef();
            def.setName(routineName);
            def.setCatalog(routineCatalog);
            def.setSchema(routineSchema);
            def.setLanguage(routineBody);
            def.setBody(routineDefinition);
            def.setComment(routineComment);
            def.setSecurityType(securityType);
            def.setParams(params);
            if ("FUNCTION".equals(routineType)) {
                def.setReturnType(dtd != null ? dtd : dataType);
            }
            // MySQL 字典不提供 VALID/INVALID 状态，保持 null
            return def;
        }
    }

    /**
     * {@code PARAMETERS} 单行原始值。
     *
     * <p>本 record 同样只做字典列到 Java 值的一比一搬运，无紧凑构造器，
     * 全部组件允许为 {@code null}。SQL 侧已用 {@code ORDINAL_POSITION > 0}
     * 过滤掉函数的返回值占位行，因此每行都对应一个真实的入参/出参。
     * 归并侧的约定见 {@code readRoutines(String)}：类型取 {@code dtd} 优先、
     * 方向缺省为 {@code IN}。</p>
     *
     * @param specificName     本参数所属的例程名，取自 {@code SPECIFIC_NAME}（过程名或函数名）；
     *                         与 {@link #routineType} 拼成 {@code ROUTINE_TYPE.SPECIFIC_NAME}
     *                         归并键去 {@code RoutineRow} 上取参数列表，不允许为 {@code null}
     * @param routineType      本参数所属例程的类型，取自 {@code ROUTINE_TYPE}，取值
     *                         {@code PROCEDURE} 或 {@code FUNCTION}；参与归并键，不允许为 {@code null}
     * @param ordinalPosition  该参数在形参列表中的序号，取自 {@code ORDINAL_POSITION}，
     *                         从 1 开始连续递增（函数返回值占位行已被 SQL 过滤）；
     *                         以 {@code getInt} + {@code wasNull} 读取，列值为 SQL {@code NULL} 时为 {@code null}，
     *                         写入 {@code ProcedureParamDef.setPosition}
     * @param mode             参数方向，取自 {@code PARAMETER_MODE}，取值
     *                         {@code IN} / {@code OUT} / {@code INOUT}；空白归一为 {@code null}，
     *                         {@code readRoutines(String)} 对 {@code null} 缺省按 {@code IN} 处理
     * @param parameterName    参数名，取自 {@code PARAMETER_NAME}；空白归一为 {@code null}
     *                         （本工具不做重命名补全），写入 {@code ProcedureParamDef.setName}
     * @param dataType         参数的数据类型名（不含长度/精度），取自 {@code DATA_TYPE}，
     *                         如 {@code int}、{@code varchar}；空白归一为 {@code null}
     * @param dtd              参数的完整数据类型描述，取自 {@code DTD_IDENTIFIER}，
     *                         如 {@code int(11)}、{@code varchar(64)}、{@code decimal(10,2)}；
     *                         {@code readRoutines(String)} 优先用它作为参数类型，缺失时回退 {@link #dataType}
     * @author CH
     * @since 4.0.0
     */
    private record ParamRow(String specificName, String routineType, Integer ordinalPosition, String mode,
                            String parameterName, String dataType, String dtd) {

        /**
         * 读取结果集当前行。
         *
         * @param rs 结果集
         * @return 行值
         * @throws SQLException 读取失败
         */
        static ParamRow read(ResultSet rs) throws SQLException {
            int ordinal = rs.getInt("ORDINAL_POSITION");
            return new ParamRow(rs.getString("SPECIFIC_NAME"), rs.getString("ROUTINE_TYPE"),
                    rs.wasNull() ? null : ordinal, MysqlMetaData.trimToNull(rs.getString("PARAMETER_MODE")),
                    MysqlMetaData.trimToNull(rs.getString("PARAMETER_NAME")),
                    MysqlMetaData.trimToNull(rs.getString("DATA_TYPE")),
                    MysqlMetaData.trimToNull(rs.getString("DTD_IDENTIFIER")));
        }
    }

    /**
     * MySQL 建存储过程链式构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class MysqlProcedureCreateBuilder implements ProcedureCreateBuilder {

        /**
         * 所属过程元数据入口
         */
        private final MysqlMetaProcedure metaProcedure;
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

        MysqlProcedureCreateBuilder(MysqlMetaProcedure metaProcedure, String procedureName) {
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
            String upper = direction == null ? "" : direction.trim().toUpperCase();
            if (!"IN".equals(upper) && !"OUT".equals(upper) && !"INOUT".equals(upper)) {
                throw new IllegalArgumentException("MySQL 过程参数方向仅支持 IN / OUT / INOUT，实际为: " + direction);
            }
            params.add(upper + " " + MysqlMetaData.quote(name) + " "
                    + MysqlMetaData.checkDdlFragment("参数类型", type));
            return this;
        }

        @Override
        public ProcedureCreateBuilder body(String body) {
            this.body.append(body);
            return this;
        }

        @Override
        public ProcedureCreateBuilder language(String language) {
            if (language != null && !"SQL".equalsIgnoreCase(language.trim())) {
                throw new UnsupportedOperationException("MySQL 存储过程只支持 SQL 语言，实际为: " + language);
            }
            return this;
        }

        @Override
        public ProcedureCreateBuilder securityType(String securityType) {
            String upper = securityType == null ? null : securityType.trim().toUpperCase();
            if (upper != null && !"DEFINER".equals(upper) && !"INVOKER".equals(upper)) {
                throw new IllegalArgumentException("MySQL 安全类型仅支持 DEFINER / INVOKER，实际为: " + securityType);
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
            if (body.isEmpty()) {
                throw new IllegalStateException("存储过程体不能为空");
            }
            if (orReplace) {
                metaProcedure.drop(procedureName);
            }
            StringBuilder sb = new StringBuilder("CREATE PROCEDURE ")
                    .append(metaProcedure.qualified(procedureName)).append('(')
                    .append(String.join(", ", params)).append(")\n");
            if (comment != null && !comment.isEmpty()) {
                sb.append("COMMENT '").append(MysqlMetaData.escapeSql(comment)).append("'\n");
            }
            if (securityType != null) {
                sb.append("SQL SECURITY ").append(securityType).append('\n');
            }
            sb.append("BEGIN\n  ")
                    .append(body.toString().replace("\n", "\n  "))
                    .append("\nEND");
            MysqlMetaData.execute(metaProcedure.engine, "创建存储过程 " + procedureName, sb.toString(), List.of());
            return metaProcedure.get(procedureName);
        }
    }
}
