package com.chua.common.support.lang.datasource.engine.wrapper;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.common.support.lang.datasource.engine.Engine;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * SQL 文本查询构建器，提供"拼接即执行"的链式查询能力。
 * <p>
 * 与 {@link LambdaQueryWrapper} 的区别很关键：本类<b>不是 ORM</b>。
 * 它不解析实体、不做对象关系映射、不感知字段注解，只把调用方给出的
 * 表名 / 视图名 / 列名 / 条件片段按 SQL 语法拼成一条语句，然后交给
 * {@link Engine} 的既有语句通道执行。因此它适用于：
 * <ul>
 *   <li>需要自由书写 SQL 片段（函数、CASE、窗口函数等）而不想放弃链式手感；</li>
 *   <li>查询目标不是实体类，而是任意表/视图，结果直接以 Map 行返回。</li>
 * </ul>
 * </p>
 * <p>
 * 支持的组成能力：{@link #select} / {@link #table} / {@link #view} /
 * {@link #innerJoin} / {@link #leftJoin} / {@link #rightJoin} /
 * {@link #where} / {@link #groupBy} / {@link #having} /
 * {@link #orderByAsc} / {@link #orderByDesc} / {@link #limit} / {@link #offset}。
 * </p>
 * <p>
 * 终端执行方法：
 * <ul>
 *   <li>{@link #list()} —— 查询结果行（Map 列表）</li>
 *   <li>{@link #list(Class)} —— 查询结果行（类型化列表）</li>
 *   <li>{@link #map()} —— 查询结果首行（Map）</li>
 *   <li>{@link #map(Class)} —— 查询结果首行（类型化对象）</li>
 *   <li>{@link #execute()} —— 执行组装好的查询语句，返回结果行数</li>
 *   <li>{@link #listAsync()} / {@link #executeAsync()} —— 异步版本，返回 {@link Mono}</li>
 * </ul>
 * </p>
 * <p>
 * <b>安全口径</b>：所有会拼进 SQL 结构的标识符（来源表/视图、别名、投影列、
 * 分组列、排序列、JOIN 表名与别名、聚合函数名）都先过
 * {@link SqlName#isSafe(String)} 白名单，非法即抛 {@link IllegalArgumentException}，
 * 因此这些位点不可能被改写语句结构。条件片段（WHERE / HAVING / JOIN ON）
 * 与 {@link JoinClause} 的既有约定一致，属于<b>受控 SQL 片段</b>，由调用方编写，
 * 其中的值必须用 {@code ?} 占位符并随方法参数传入以完成参数化绑定。
 * </p>
 * <p>
 * <b>无状态终端</b>：终端方法不修改构建器状态，同一个构建器可重复执行；
 * {@link #sql()} 可在执行前预览或记录将被下发的语句。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * List<Map<String, Object>> rows = engine.query()
 *     .select("u.id", "u.name", "o.amount")
 *     .table("t_user", "u")
 *     .innerJoin("t_order o", "u.id = o.user_id")
 *     .where("u.age > ?", 18)
 *     .groupBy("u.id", "u.name", "o.amount")
 *     .having("SUM(o.amount) > ?", 100)
 *     .orderByDesc("o.amount")
 *     .limit(20)
 *     .offset(0)
 *     .list();
 *
 * int hitCount = engine.query()
 *     .table("t_user")
 *     .where("id = ?", 1)
 *     .execute();          // 查询命中行数
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see Engine#query()
 * @see LambdaQueryWrapper
 */
public final class SqlQueryBuilder {

    /**
     * 来源类型：物理表
     */
    private static final String KIND_TABLE = "TABLE";

    /**
     * 来源类型：视图
     */
    private static final String KIND_VIEW = "VIEW";

    /**
     * 执行语句的引擎，非 null
     */
    private final Engine engine;

    /**
     * 投影列，空表示 {@code *}
     */
    private final List<String> selectColumns = Wraps.list();

    /**
     * JOIN 关联子句
     */
    private final List<JoinClause> joins = Wraps.list();

    /**
     * WHERE 条件片段（每段自带 AND/OR 连接词，首段不含），不含 WHERE 关键字
     */
    private final List<String> whereFragments = Wraps.list();

    /**
     * WHERE 绑定参数，与 {@code ?} 占位符顺序一致
     */
    private final List<Object> params = Wraps.list();

    /**
     * GROUP BY 列
     */
    private final List<String> groupBys = Wraps.list();

    /**
     * HAVING 绑定参数，与 {@code ?} 占位符顺序一致
     */
    private final List<Object> havingParams = Wraps.list();

    /**
     * ORDER BY 片段，每项形如 {@code 列名 ASC}
     */
    private final List<String> orderBys = Wraps.list();

    /**
     * 来源表或视图名，null 表示尚未指定
     */
    private String source;

    /**
     * 来源别名，null 表示不使用别名
     */
    private String sourceAlias;

    /**
     * 来源类型：{@link #KIND_TABLE} 或 {@link #KIND_VIEW}，仅作语义标注
     */
    private String sourceKind = KIND_TABLE;

    /**
     * HAVING 条件片段，null 表示无分组过滤
     */
    private String havingClause;

    /**
     * 返回行数上限，0 表示不限制
     */
    private int limit;

    /**
     * 偏移行数，0 表示不偏移
     */
    private int offset;

    /**
     * 构造构建器。
     *
     * @param engine 执行语句的引擎，不允许为 null
     * @throws IllegalArgumentException 引擎为 null 时抛出
     */
    public SqlQueryBuilder(Engine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("引擎不能为空");
        }
        this.engine = engine;
    }

    /**
     * 创建构建器。
     *
     * @param engine 执行语句的引擎
     * @return 构建器实例
     * @see Engine#query()
     */
    public static SqlQueryBuilder of(Engine engine) {
        return new SqlQueryBuilder(engine);
    }

    // ==================== SELECT ====================

    /**
     * 添加投影列。
     *
     * @param columns 列名（可带表限定，如 {@code "u.name"}）或 {@code *}，至少一个
     * @return this
     * @throws IllegalArgumentException 列为空或不是合法标识符时抛出
     */
    public SqlQueryBuilder select(String... columns) {
        if (columns == null || columns.length == 0) {
            throw new IllegalArgumentException("SELECT 列不能为空");
        }
        for (String column : columns) {
            selectColumns.add(checkProjection(column));
        }
        return this;
    }

    /**
     * 添加聚合函数投影列，渲染为 {@code 函数(列) AS 别名}。
     *
     * @param function 聚合函数名，如 SUM / AVG / MAX / MIN / COUNT
     * @param column   聚合列名，null、空串或 {@code *} 表示 {@code 函数(*)}
     * @param alias    结果别名，null 或空串时不加 AS 子句
     * @return this
     * @throws IllegalArgumentException 函数名非法时抛出
     */
    public SqlQueryBuilder selectFunc(String function, String column, String alias) {
        if (function == null || function.isBlank()) {
            throw new IllegalArgumentException("聚合函数名不能为空");
        }
        checkIdentifier(function, "聚合函数名");
        String expr = column == null || column.isBlank() || "*".equals(column)
                ? function + "(*)"
                : function + "(" + checkIdentifier(column, "聚合列名") + ")";
        if (alias != null && !alias.isBlank()) {
            expr = expr + " AS " + checkIdentifier(alias, "聚合别名");
        }
        selectColumns.add(expr);
        return this;
    }

    /**
     * 添加 {@code COUNT(*)} 聚合投影列。
     *
     * @param alias 结果别名，null 或空串时不加 AS 子句
     * @return this
     */
    public SqlQueryBuilder selectCount(String alias) {
        return selectFunc("COUNT", "*", alias);
    }

    // ==================== 来源 ====================

    /**
     * 指定查询来源表。
     *
     * @param table 表名，可带一段 schema 限定（如 {@code "db.t_user"}）
     * @return this
     * @throws IllegalArgumentException 表名不是合法标识符时抛出
     */
    public SqlQueryBuilder table(String table) {
        return from(table, null, KIND_TABLE);
    }

    /**
     * 指定查询来源表与别名。
     *
     * @param table 表名，可带一段 schema 限定
     * @param alias 表别名
     * @return this
     * @throws IllegalArgumentException 表名或别名不是合法标识符时抛出
     */
    public SqlQueryBuilder table(String table, String alias) {
        return from(table, alias, KIND_TABLE);
    }

    /**
     * 指定查询来源视图。
     * <p>视图与表在 SQL 中处于同一位置，本方法只做语义标注，渲染结果与
     * {@link #table(String)} 一致。</p>
     *
     * @param view 视图名，可带一段 schema 限定
     * @return this
     * @throws IllegalArgumentException 视图名不是合法标识符时抛出
     */
    public SqlQueryBuilder view(String view) {
        return from(view, null, KIND_VIEW);
    }

    /**
     * 指定查询来源视图与别名。
     *
     * @param view  视图名，可带一段 schema 限定
     * @param alias 视图别名
     * @return this
     * @throws IllegalArgumentException 视图名或别名不是合法标识符时抛出
     */
    public SqlQueryBuilder view(String view, String alias) {
        return from(view, alias, KIND_VIEW);
    }

    /**
     * 指定来源（表或视图）。
     *
     * @param name  来源名
     * @param alias 别名，null 或空串表示不使用别名
     * @param kind  来源类型标注
     * @return this
     */
    private SqlQueryBuilder from(String name, String alias, String kind) {
        this.source = checkIdentifier(name, kind.equals(KIND_VIEW) ? "视图名" : "表名");
        this.sourceAlias = alias == null || alias.isBlank() ? null : checkIdentifier(alias, "来源别名");
        this.sourceKind = kind;
        return this;
    }

    /**
     * 获取来源类型标注。
     *
     * @return {@code TABLE} 或 {@code VIEW}
     */
    public String getSourceKind() {
        return sourceKind;
    }

    // ==================== JOIN ====================

    /**
     * 添加 INNER JOIN 关联。
     *
     * @param table       关联表名，可携带别名（如 {@code "t_order o"}）
     * @param onCondition ON 关联条件 SQL 片段，如 {@code "u.id = o.user_id"}
     * @return this
     * @throws IllegalArgumentException 表名或 ON 条件为空、表名含非法标识符时抛出
     */
    public SqlQueryBuilder innerJoin(String table, String onCondition) {
        return addJoin("INNER", table, null, onCondition);
    }

    /**
     * 添加 INNER JOIN 关联（显式别名）。
     *
     * @param table       关联表名
     * @param alias       表别名
     * @param onCondition ON 关联条件 SQL 片段
     * @return this
     */
    public SqlQueryBuilder innerJoin(String table, String alias, String onCondition) {
        return addJoin("INNER", table, alias, onCondition);
    }

    /**
     * 添加 LEFT JOIN 关联。
     *
     * @param table       关联表名，可携带别名
     * @param onCondition ON 关联条件 SQL 片段
     * @return this
     */
    public SqlQueryBuilder leftJoin(String table, String onCondition) {
        return addJoin("LEFT", table, null, onCondition);
    }

    /**
     * 添加 LEFT JOIN 关联（显式别名）。
     *
     * @param table       关联表名
     * @param alias       表别名
     * @param onCondition ON 关联条件 SQL 片段
     * @return this
     */
    public SqlQueryBuilder leftJoin(String table, String alias, String onCondition) {
        return addJoin("LEFT", table, alias, onCondition);
    }

    /**
     * 添加 RIGHT JOIN 关联。
     *
     * @param table       关联表名，可携带别名
     * @param onCondition ON 关联条件 SQL 片段
     * @return this
     */
    public SqlQueryBuilder rightJoin(String table, String onCondition) {
        return addJoin("RIGHT", table, null, onCondition);
    }

    /**
     * 添加 RIGHT JOIN 关联（显式别名）。
     *
     * @param table       关联表名
     * @param alias       表别名
     * @param onCondition ON 关联条件 SQL 片段
     * @return this
     */
    public SqlQueryBuilder rightJoin(String table, String alias, String onCondition) {
        return addJoin("RIGHT", table, alias, onCondition);
    }

    /**
     * 追加一条 JOIN 关联子句。
     *
     * @param joinType    JOIN 类型
     * @param table       关联表名，可写作"表名 别名"两段
     * @param alias       显式别名，可为 null
     * @param onCondition ON 条件
     * @return this
     */
    private SqlQueryBuilder addJoin(String joinType, String table, String alias, String onCondition) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("JOIN 表名不能为空");
        }
        if (onCondition == null || onCondition.isBlank()) {
            throw new IllegalArgumentException("JOIN ON 条件不能为空");
        }
        // 表名允许"表名 别名"两段写法，各段均须为合法标识符
        String[] tokens = table.trim().split("\\s+");
        if (tokens.length > 2) {
            throw new IllegalArgumentException("非法 JOIN 表名: " + table);
        }
        checkIdentifier(tokens[0], "JOIN 表名");
        if (tokens.length == 2) {
            checkIdentifier(tokens[1], "JOIN 别名");
        }
        if (alias != null && !alias.isBlank()) {
            checkIdentifier(alias, "JOIN 别名");
        }
        joins.add(new JoinClause(joinType, table.trim(), alias, onCondition));
        return this;
    }

    // ==================== WHERE ====================

    /**
     * 追加 WHERE 条件（多个条件之间为 AND）。
     *
     * @param condition 条件片段，值用 {@code ?} 占位，如 {@code "age > ? AND status = ?"}
     * @param params    与占位符顺序一致的绑定值
     * @return this
     * @throws IllegalArgumentException 条件为空时抛出
     */
    public SqlQueryBuilder where(String condition, Object... params) {
        return appendCondition("AND", condition, params, false);
    }

    /**
     * 以 AND 追加 WHERE 条件，须跟在首个条件之后。
     *
     * @param condition 条件片段
     * @param params    绑定值
     * @return this
     * @throws IllegalArgumentException 条件为空或尚无首个条件时抛出
     */
    public SqlQueryBuilder and(String condition, Object... params) {
        return appendCondition("AND", condition, params, true);
    }

    /**
     * 以 OR 追加 WHERE 条件，须跟在首个条件之后。
     *
     * @param condition 条件片段
     * @param params    绑定值
     * @return this
     * @throws IllegalArgumentException 条件为空或尚无首个条件时抛出
     */
    public SqlQueryBuilder or(String condition, Object... params) {
        return appendCondition("OR", condition, params, true);
    }

    /**
     * 追加一条 WHERE 条件片段。
     *
     * @param operator        连接词 AND / OR
     * @param condition       条件片段
     * @param values          绑定值
     * @param requireExisting 是否要求已存在首个条件
     * @return this
     */
    private SqlQueryBuilder appendCondition(String operator, String condition, Object[] values, boolean requireExisting) {
        if (condition == null || condition.isBlank()) {
            throw new IllegalArgumentException("WHERE 条件不能为空");
        }
        if (requireExisting && whereFragments.isEmpty()) {
            throw new IllegalArgumentException(operator + " 条件必须跟在首个 WHERE 条件之后");
        }
        whereFragments.add(whereFragments.isEmpty() ? condition : operator + " " + condition);
        addParams(params, values);
        return this;
    }

    // ==================== GROUP BY / HAVING ====================

    /**
     * 添加 GROUP BY 列。
     *
     * @param columns 分组列，至少一个
     * @return this
     * @throws IllegalArgumentException 列为空或不是合法标识符时抛出
     */
    public SqlQueryBuilder groupBy(String... columns) {
        if (columns == null || columns.length == 0) {
            throw new IllegalArgumentException("GROUP BY 列不能为空");
        }
        for (String column : columns) {
            groupBys.add(checkIdentifier(column, "GROUP BY 列"));
        }
        return this;
    }

    /**
     * 添加 HAVING 分组过滤条件，须与 {@link #groupBy} 配合使用。
     * <p>参数绑定顺序在 WHERE 参数之后。</p>
     *
     * @param condition 条件片段，如 {@code "SUM(amount) > ?"}
     * @param params    绑定值
     * @return this
     * @throws IllegalArgumentException 条件为空时抛出
     */
    public SqlQueryBuilder having(String condition, Object... params) {
        if (condition == null || condition.isBlank()) {
            throw new IllegalArgumentException("HAVING 条件不能为空");
        }
        this.havingClause = condition;
        addParams(havingParams, params);
        return this;
    }

    // ==================== ORDER BY / 分页 ====================

    /**
     * 添加 ORDER BY 列（升序）。
     *
     * @param columns 排序列，至少一个
     * @return this
     */
    public SqlQueryBuilder orderBy(String... columns) {
        return orderByAsc(columns);
    }

    /**
     * 添加 ORDER BY 列（升序）。
     *
     * @param columns 排序列，至少一个
     * @return this
     * @throws IllegalArgumentException 列为空或不是合法标识符时抛出
     */
    public SqlQueryBuilder orderByAsc(String... columns) {
        return addOrderBy(true, columns);
    }

    /**
     * 添加 ORDER BY 列（降序）。
     *
     * @param columns 排序列，至少一个
     * @return this
     * @throws IllegalArgumentException 列为空或不是合法标识符时抛出
     */
    public SqlQueryBuilder orderByDesc(String... columns) {
        return addOrderBy(false, columns);
    }

    /**
     * 追加排序片段。
     *
     * @param asc     是否升序
     * @param columns 排序列
     * @return this
     */
    private SqlQueryBuilder addOrderBy(boolean asc, String... columns) {
        if (columns == null || columns.length == 0) {
            throw new IllegalArgumentException("ORDER BY 列不能为空");
        }
        for (String column : columns) {
            orderBys.add(checkIdentifier(column, "ORDER BY 列") + (asc ? " ASC" : " DESC"));
        }
        return this;
    }

    /**
     * 限制返回行数。
     *
     * @param limit 行数上限，必须大于 0
     * @return this
     * @throws IllegalArgumentException 上限不大于 0 时抛出
     */
    public SqlQueryBuilder limit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        this.limit = limit;
        return this;
    }

    /**
     * 设置偏移行数。
     * <p>部分方言（如 MySQL）要求 OFFSET 与 LIMIT 同时出现，需要分页时请一并调用
     * {@link #limit(int)}。</p>
     *
     * @param offset 偏移行数，不能为负
     * @return this
     * @throws IllegalArgumentException 偏移为负时抛出
     */
    public SqlQueryBuilder offset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不能为负");
        }
        this.offset = offset;
        return this;
    }

    // ==================== SQL 渲染 ====================

    /**
     * 渲染当前构建状态对应的 SQL 语句。
     *
     * @return SQL 语句（含 {@code ?} 占位符）
     * @throws IllegalStateException 尚未指定来源表或视图时抛出
     */
    public String sql() {
        if (source == null) {
            throw new IllegalStateException("未指定查询来源, 请先调用 table(...) 或 view(...)");
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append("SELECT ").append(selectColumns.isEmpty() ? "*" : String.join(", ", selectColumns));
        sb.append(" FROM ").append(source);
        if (sourceAlias != null) {
            sb.append(' ').append(sourceAlias);
        }
        for (JoinClause join : joins) {
            sb.append(' ').append(join.joinType()).append(" JOIN ")
                    .append(join.renderTable()).append(" ON ").append(join.onCondition());
        }
        if (!whereFragments.isEmpty()) {
            sb.append(" WHERE ").append(String.join(" ", whereFragments));
        }
        if (!groupBys.isEmpty()) {
            sb.append(" GROUP BY ").append(String.join(", ", groupBys));
        }
        if (havingClause != null) {
            sb.append(" HAVING ").append(havingClause);
        }
        if (!orderBys.isEmpty()) {
            sb.append(" ORDER BY ").append(String.join(", ", orderBys));
        }
        if (limit > 0) {
            sb.append(" LIMIT ").append(limit);
        }
        if (offset > 0) {
            sb.append(" OFFSET ").append(offset);
        }
        return sb.toString();
    }

    /**
     * 获取全部绑定参数，顺序为 WHERE 参数在前、HAVING 参数在后。
     *
     * @return 参数数组
     */
    public Object[] boundParams() {
        List<Object> all = Wraps.list(params.size() + havingParams.size());
        all.addAll(params);
        all.addAll(havingParams);
        return all.toArray();
    }

    // ==================== 终端执行 ====================

    /**
     * 执行查询，返回 Map 行列表。
     *
     * @return 结果行列表
     * @throws UnsupportedOperationException 引擎不支持原生查询时抛出
     */
    public List<Map<String, Object>> list() {
        return engine.querySql(sql(), boundParams());
    }

    /**
     * 执行查询，返回类型化对象列表。
     *
     * @param rowType 行类型
     * @param <R>     行类型参数
     * @return 结果对象列表
     * @throws IllegalArgumentException 行类型为 null 时抛出
     */
    public <R> List<R> list(Class<R> rowType) {
        requireRowType(rowType);
        return engine.querySql(sql(), rowType, boundParams());
    }

    /**
     * 执行查询，返回首行 Map。
     * <p>与 {@link Engine#queryOne} 口径一致：命中多行返回首行，无命中返回 null。</p>
     *
     * @return 首行 Map，无结果返回 null
     */
    public Map<String, Object> map() {
        List<Map<String, Object>> rows = list();
        if (rows == null || rows.isEmpty() || rows.getFirst() == null) {
            return null;
        }
        return rows.getFirst();
    }

    /**
     * 执行查询，返回首个类型化对象。
     *
     * @param rowType 行类型
     * @param <R>     行类型参数
     * @return 首个对象，无结果返回 null
     * @throws IllegalArgumentException 行类型为 null 时抛出
     */
    public <R> R map(Class<R> rowType) {
        requireRowType(rowType);
        List<R> rows = list(rowType);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.getFirst();
    }

    /**
     * 执行组装好的查询语句，返回结果行数。
     * <p>本构建器只组装查询语句，因此执行即查询；需要结果本身时请使用
     * {@link #list()} / {@link #map()}。若只关心"命中多少行"，本方法避免物化结果集。</p>
     *
     * @return 查询结果行数
     * @throws UnsupportedOperationException 引擎不支持原生查询时抛出
     */
    public int execute() {
        List<Map<String, Object>> rows = list();
        return rows == null ? 0 : rows.size();
    }

    /**
     * 异步执行查询。
     *
     * @return 结果行列表的 Mono
     */
    public Mono<List<Map<String, Object>>> listAsync() {
        return Mono.fromCallable(this::list).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 异步执行组装好的语句。
     *
     * @return 行数结果的 Mono
     * @see #execute()
     */
    public Mono<Integer> executeAsync() {
        return Mono.fromCallable(this::execute).subscribeOn(Schedulers.boundedElastic());
    }

    // ==================== 内部实现 ====================

    /**
     * 追加绑定参数。
     *
     * @param target 目标列表
     * @param values 绑定值，可为 null
     */
    private static void addParams(List<Object> target, Object[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        // 用 Collections.addAll 而非 List.of：绑定值允许为 null，List.of 会直接 NPE
        Collections.addAll(target, values);
    }

    /**
     * 校验投影列：标识符白名单，额外放行 {@code *}。
     *
     * @param column 列名
     * @return 校验通过的列名
     */
    private static String checkProjection(String column) {
        if ("*".equals(column)) {
            return column;
        }
        return checkIdentifier(column, "SELECT 列");
    }

    /**
     * 校验标识符：拒绝空白、引号、分号、空白符等可改写语句结构的字符。
     *
     * @param name  待校验标识符
     * @param label 用途，用于异常定位
     * @return 校验通过的标识符
     * @throws IllegalArgumentException 标识符为空或不在白名单内时抛出
     */
    private static String checkIdentifier(String name, String label) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (!SqlName.isSafe(name)) {
            throw new IllegalArgumentException(label + "非法: " + name);
        }
        return name;
    }

    /**
     * 校验行类型。
     *
     * @param rowType 行类型
     * @param <R>     行类型参数
     */
    private static <R> void requireRowType(Class<R> rowType) {
        if (rowType == null) {
            throw new IllegalArgumentException("行类型不能为空");
        }
    }
}
