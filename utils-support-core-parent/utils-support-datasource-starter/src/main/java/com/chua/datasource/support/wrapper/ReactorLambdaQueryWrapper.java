package com.chua.datasource.support.wrapper;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.engine.wrapper.AbstractLambdaWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.JoinClause;
import com.chua.common.support.lang.datasource.engine.wrapper.QuerySql;
import com.chua.common.support.lang.datasource.engine.wrapper.SFunction;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.datasource.support.wrapper.toolkit.LambdaUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 响应式 Lambda 查询包装器，条件 API 与 {@code LambdaQueryWrapper} 一致，
 * 终端方法返回 Reactor 响应式类型（{@link Flux} / {@link Mono}）。
 *
 * <p>底层通过 {@link Schedulers#boundedElastic()} 调度阻塞的 JDBC 执行，
 * 避免阻塞 Reactor 事件循环线程。</p>
 *
 * <pre>{@code
 * Flux<User> users = engine.query(User.class)
 *     .eq(User::getName, "张三")
 *     .gt(User::getAge, 18)
 *     .list();
 * }</pre>
 *
 * @param <T> 实体类型
 * @author CH
 * @since 4.0.0.42
 */
public class ReactorLambdaQueryWrapper<T> extends AbstractLambdaWrapper<T, ReactorLambdaQueryWrapper<T>> {

    /**
     * 底层同步引擎
     */
    private final Engine engine;

    /**
     * 查询列列表
     */
    private final List<String> selectColumns = new ArrayList<>();

    /**
     * 分组列名
     */
    private String groupByColumn;

    /**
     * 返回行数上限，0 表示不限制
     */
    private int limit;

    /**
     * 偏移行数，0 表示不偏移
     */
    private int offset;

    /**
     * JOIN 关联子句列表
     */
    private final List<JoinClause> joins = new ArrayList<>();

    /**
     * HAVING 条件片段（不含 HAVING 关键字），null 表示无分组过滤
     */
    private String havingClause;

    /**
     * HAVING 条件参数列表（与 ? 占位符顺序一致）
     */
    private final List<Object> havingParams = new ArrayList<>();

    /**
     * 创建响应式查询包装器。
     *
     * @param engine      底层引擎
     * @param entityClass 实体类
     */
    public ReactorLambdaQueryWrapper(Engine engine, Class<T> entityClass) {
        super(entityClass);
        this.engine = engine;
    }

    // ==================== SELECT / GROUP BY ====================

    /**
     * 添加 选择 列。
     * @param column column
     * @return 选择的结果
     */
    public ReactorLambdaQueryWrapper<T> select(SFunction<T, ?> column) {
        selectColumns.add(resolveColumn(column));
        return this;
    }

    /**
     * 批量添加 选择 列。
     * @param columns columns
     * @return 选择的结果
     */
    @SafeVarargs
    public final ReactorLambdaQueryWrapper<T> select(SFunction<T, ?>... columns) {
        for (SFunction<T, ?> c : columns) {
            select(c);
        }
        return this;
    }

    /**
     * 以字符串形式添加 选择 列。
     * @param columns columns
     * @return 选择的结果
     */
    public ReactorLambdaQueryWrapper<T> select(String... columns) {
        if (columns != null) {
            for (String column : columns) {
                selectColumns.add(checkSelectColumn(column));
            }
        }
        return this;
    }

    /**
     * 校验字符串 SELECT 列：标识符白名单，额外放行 {@code *}。
     *
     * @param column 列名
     * @return 校验通过的列名
     */
    private static String checkSelectColumn(String column) {
        if ("*".equals(column)) {
            return column;
        }
        return checkIdentifier(column);
    }

    /**
     * 添加 群体 BY 列。
     * @param column column
     * @return 群体by的结果
     */
    public ReactorLambdaQueryWrapper<T> groupBy(SFunction<T, ?> column) {
        this.groupByColumn = resolveColumn(column);
        return this;
    }

    /**
     * 以字符串形式添加 GROUP BY 列，支持多列分组。
     *
     * @param columns 列名数组，至少一个
     * @return this
     */
    public ReactorLambdaQueryWrapper<T> groupBy(String... columns) {
        if (columns == null || columns.length == 0) {
            throw new IllegalArgumentException("GROUP BY 列不能为空");
        }
        String[] checked = new String[columns.length];
        for (int i = 0; i < columns.length; i++) {
            checked[i] = checkIdentifier(columns[i]);
        }
        this.groupByColumn = String.join(", ", checked);
        return this;
    }

    // ==================== JOIN ====================

    /**
     * 添加 INNER JOIN 关联。
     *
     * @param table       关联表名，可携带别名（如 {@code "order o"}）
     * @param onCondition ON 关联条件 SQL 片段
     * @return this
     */
    public ReactorLambdaQueryWrapper<T> innerJoin(String table, String onCondition) {
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
    public ReactorLambdaQueryWrapper<T> innerJoin(String table, String alias, String onCondition) {
        return addJoin("INNER", table, alias, onCondition);
    }

    /**
     * 添加 LEFT JOIN 关联。
     *
     * @param table       关联表名，可携带别名（如 {@code "order o"}）
     * @param onCondition ON 关联条件 SQL 片段
     * @return this
     */
    public ReactorLambdaQueryWrapper<T> leftJoin(String table, String onCondition) {
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
    public ReactorLambdaQueryWrapper<T> leftJoin(String table, String alias, String onCondition) {
        return addJoin("LEFT", table, alias, onCondition);
    }

    /**
     * 添加 RIGHT JOIN 关联。
     *
     * @param table       关联表名，可携带别名（如 {@code "order o"}）
     * @param onCondition ON 关联条件 SQL 片段
     * @return this
     */
    public ReactorLambdaQueryWrapper<T> rightJoin(String table, String onCondition) {
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
    public ReactorLambdaQueryWrapper<T> rightJoin(String table, String alias, String onCondition) {
        return addJoin("RIGHT", table, alias, onCondition);
    }

    /**
     * 追加一条 JOIN 关联子句（内部方法）。
     *
     * @param joinType    JOIN 类型：INNER、LEFT 或 RIGHT
     * @param table       关联表名
     * @param alias       表别名，可为 null
     * @param onCondition ON 关联条件 SQL 片段
     * @return this
     */
    private ReactorLambdaQueryWrapper<T> addJoin(String joinType, String table, String alias, String onCondition) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("JOIN 表名不能为空");
        }
        if (onCondition == null || onCondition.isBlank()) {
            throw new IllegalArgumentException("JOIN ON 条件不能为空");
        }
        // 表名允许 "表名 别名" 两段写法，各段均须为合法标识符
        String[] tokens = table.trim().split("\\s+");
        if (tokens.length > 2) {
            throw new IllegalArgumentException("非法 JOIN 表名: " + table);
        }
        checkIdentifier(tokens[0]);
        if (tokens.length == 2) {
            checkIdentifier(tokens[1]);
        }
        if (alias != null && !alias.isBlank()) {
            checkIdentifier(alias);
        }
        joins.add(new JoinClause(joinType, table.trim(), alias, onCondition));
        return this;
    }

    // ==================== 聚合投影 / HAVING ====================

    /**
     * 添加聚合函数投影列，渲染为 {@code 函数(列) AS 别名}。
     *
     * @param function 聚合函数名，如 SUM、AVG、MAX、MIN、COUNT
     * @param column   聚合列名，null 或空串表示 {@code *}
     * @param alias    结果别名，null 或空串时不加 AS 子句
     * @return this
     */
    public ReactorLambdaQueryWrapper<T> selectFunc(String function, String column, String alias) {
        if (function == null || function.isBlank()) {
            throw new IllegalArgumentException("聚合函数名不能为空");
        }
        checkIdentifier(function);
        String expr = column == null || column.isBlank() || "*".equals(column)
                ? function + "(*)"
                : function + "(" + checkIdentifier(column) + ")";
        if (alias != null && !alias.isBlank()) {
            expr = expr + " AS " + checkIdentifier(alias);
        }
        selectColumns.add(expr);
        return this;
    }

    /**
     * 添加 COUNT(*) 聚合投影列。
     *
     * @param alias 结果别名
     * @return this
     */
    public ReactorLambdaQueryWrapper<T> selectCount(String alias) {
        return selectFunc("COUNT", "*", alias);
    }

    /**
     * 添加 SUM(列) 聚合投影列。
     *
     * @param column 聚合列名
     * @param alias  结果别名
     * @return this
     */
    public ReactorLambdaQueryWrapper<T> selectSum(String column, String alias) {
        return selectFunc("SUM", column, alias);
    }

    /**
     * 添加 AVG(列) 聚合投影列。
     *
     * @param column 聚合列名
     * @param alias  结果别名
     * @return this
     */
    public ReactorLambdaQueryWrapper<T> selectAvg(String column, String alias) {
        return selectFunc("AVG", column, alias);
    }

    /**
     * 添加 MAX(列) 聚合投影列。
     *
     * @param column 聚合列名
     * @param alias  结果别名
     * @return this
     */
    public ReactorLambdaQueryWrapper<T> selectMax(String column, String alias) {
        return selectFunc("MAX", column, alias);
    }

    /**
     * 添加 MIN(列) 聚合投影列。
     *
     * @param column 聚合列名
     * @param alias  结果别名
     * @return this
     */
    public ReactorLambdaQueryWrapper<T> selectMin(String column, String alias) {
        return selectFunc("MIN", column, alias);
    }

    /**
     * 添加 HAVING 分组过滤条件，须与 GROUP BY 配合使用。
     * <p>条件中的 {@code ?} 占位符由 params 按顺序绑定，
     * 参数绑定顺序在 WHERE 参数之后。</p>
     *
     * @param condition HAVING 条件片段，如 {@code "SUM(amount) > ?"}
     * @param params    条件参数列表
     * @return this
     */
    public ReactorLambdaQueryWrapper<T> having(String condition, Object... params) {
        if (condition == null || condition.isBlank()) {
            throw new IllegalArgumentException("HAVING 条件不能为空");
        }
        this.havingClause = condition;
        if (params != null && params.length > 0) {
            havingParams.addAll(List.of(params));
        }
        return this;
    }

    // ==================== LIMIT / OFFSET ====================

    /**
     * 限制返回行数，下推到数据库分页语法。
     *
     * @param limit 返回行数上限，必须大于 0
     * @return this
     */
    public ReactorLambdaQueryWrapper<T> limit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        this.limit = limit;
        return this;
    }

    /**
     * 设置偏移行数，与 {@link #limit(int)} 配合实现数据库物理分页。
     *
     * @param offset 偏移行数，不能为负
     * @return this
     */
    public ReactorLambdaQueryWrapper<T> offset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不能为负");
        }
        this.offset = offset;
        return this;
    }

    // ==================== SQL 构建 ====================

    /**
     * 构建查询 SQL 信息。
     * @return 构建sql的结果
     */
    public QuerySql<T> buildSql() {
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        buildWhere(where, params);
        return new QuerySql<>(entityClass, selectColumns, where.toString(), params,
                groupByColumn, getOrderBys(), limit, offset,
                List.copyOf(joins), havingClause, List.copyOf(havingParams));
    }

    // ==================== 终端执行方法（响应式） ====================

    /**
     * 执行查询，返回实体列表的 Flux。
     *
     * @return 实体 Flux
     */
    public Flux<T> list() {
        return Mono.fromCallable(this::doList)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(list -> Flux.fromIterable(list));
    }

    /**
     * 执行查询，返回单个实体的 Mono。
     *
     * @return 实体 Mono，不存在返回空 Mono
     */
    public Mono<T> one() {
        return Mono.fromCallable(() -> {
            List<T> list = doList();
            return list.isEmpty() ? null : list.getFirst();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 执行分页查询，返回分页结果的 Mono。
     *
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页大小
     * @return 分页结果 Mono
     */
    public Mono<Page<T>> page(int pageNum, int pageSize) {
        final int pn = pageNum < 1 ? 1 : pageNum;
        final int ps = pageSize;
        if (ps < 1) {
            throw new IllegalArgumentException("每页大小必须大于 0");
        }
        return Mono.fromCallable(() -> {
            // 先执行 COUNT 获取总数，再下推分页查询当前页，避免全表加载到内存截取
            long total = executeCount();
            limit(ps).offset((pn - 1) * ps);
            List<T> records = doList();
            return new Page<T>(pn, ps, total, records);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 统计当前条件命中的总行数（不含分页参数），返回 Mono。
     *
     * @return 总行数 Mono
     */
    public Mono<Long> count() {
        return Mono.fromCallable(this::executeCount)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 统计当前条件命中的总行数（不含分页参数）。
     *
     * @return 总行数
     */
    private long executeCount() {
        QuerySql<T> sql = buildSql();
        String countSql = "SELECT COUNT(*) FROM (" + buildCoreSql(sql, false) + ") t_count";
        List<Map<String, Object>> rows = engine.getExecutor().query(countSql, mergedParams(sql));
        if (rows == null || rows.isEmpty()) {
            return 0L;
        }
        Object value = rows.getFirst().values().iterator().next();
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    /**
     * 合并 WHERE 参数与 HAVING 参数为按占位符顺序排列的绑定数组。
     *
     * @param sql 查询 SQL 信息
     * @return 参数数组（WHERE 参数在前，HAVING 参数在后）
     */
    private static <T> Object[] mergedParams(QuerySql<T> sql) {
        List<Object> all = new ArrayList<>(sql.params());
        if (sql.hasHaving()) {
            all.addAll(sql.havingParams());
        }
        return all.toArray();
    }

    /**
     * 同步执行查询（内部使用，供响应式方法在 boundedElastic 线程调用）。
     * @return 执行列表的结果
     */
    private List<T> doList() {
        QuerySql<T> sql = buildSql();
        String fullSql = buildCoreSql(sql, true);
        return engine.getExecutor().query(fullSql, entityClass, mergedParams(sql));
    }

    /**
     * 根据查询 SQL 信息构建 SELECT 语句。
     *
     * @param sql        查询 SQL 信息
     * @param withPaging 是否包含 ORDER BY 与分页子句（COUNT 包装时传 false）
     * @return SELECT 语句
     */
    private String buildCoreSql(QuerySql<T> sql, boolean withPaging) {
        String tableName = com.chua.datasource.support.engine.AbstractEngine.resolveTableName(entityClass);
        StringBuilder fullSql = new StringBuilder("SELECT ");
        if (sql.hasSelect()) {
            fullSql.append(String.join(", ", sql.selectColumns()));
        } else if (sql.hasGroupBy()) {
            // 有 GROUP BY 但无显式投影列时只选择分组列，兼容 ONLY_FULL_GROUP_BY 严格模式
            fullSql.append(sql.groupByColumn());
        } else {
            fullSql.append("*");
        }
        fullSql.append(" FROM ").append(tableName);
        // 渲染 JOIN 关联子句：类型 + 目标表[别名] + ON 条件
        if (sql.joins() != null) {
            for (JoinClause join : sql.joins()) {
                fullSql.append(" ").append(join.joinType()).append(" JOIN ")
                        .append(join.renderTable()).append(" ON ").append(join.onCondition());
            }
        }
        if (sql.hasWhere()) {
            fullSql.append(" WHERE ").append(sql.whereClause());
        }
        if (sql.hasGroupBy()) {
            fullSql.append(" GROUP BY ").append(sql.groupByColumn());
        }
        if (sql.hasHaving()) {
            fullSql.append(" HAVING ").append(sql.havingClause());
        }
        String coreSql = fullSql.toString();
        if (withPaging && sql.hasOrderBy()) {
            coreSql = coreSql + " ORDER BY " + String.join(", ", sql.orderBys());
        }
        if (withPaging && sql.limit() > 0) {
            coreSql = wrapPagination(coreSql, sql.limit(), sql.offset());
        }
        return coreSql;
    }

    /**
     * 为 SQL 追加分页子句，优先使用当前数据源方言，无方言时使用标准 LIMIT/OFFSET 兜底。
     *
     * @param coreSql 不含分页的 SQL
     * @param limit   返回行数上限
     * @param offset  偏移行数
     * @return 带分页子句的 SQL
     */
    private String wrapPagination(String coreSql, int limit, int offset) {
        Dialect dialect = engine.getDialect(engine.getDefaultDataSourceName());
        if (dialect == null || dialect.supportsLimit()) {
            return coreSql + " LIMIT " + limit + " OFFSET " + offset;
        }
        if (offset % limit == 0) {
            Pagination pagination = new Pagination()
                    .setPageNum(offset / limit + 1)
                    .setPageSize(limit);
            return dialect.processSql(coreSql, pagination);
        }
        throw new UnsupportedOperationException("当前方言（" + dialect.protocol()
                + "）不支持任意偏移分页，offset 必须是 limit 的整数倍: limit=" + limit + ", offset=" + offset);
    }

    // ==================== 内部实现 ====================

    @Override
    protected ReactorLambdaQueryWrapper<T> newInstance() {
        return new ReactorLambdaQueryWrapper<>(engine, entityClass);
    }

    @Override
    protected String resolveColumn(SFunction<T, ?> column) {
        return LambdaUtils.resolveColumn(column);
    }
}
