package com.chua.datasource.support.wrapper;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.wrapper.AbstractLambdaWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.Condition;
import com.chua.common.support.lang.datasource.engine.wrapper.QuerySql;
import com.chua.common.support.lang.datasource.engine.wrapper.SFunction;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.datasource.support.wrapper.toolkit.LambdaUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

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
     * 添加 SELECT 列。
     */
    public ReactorLambdaQueryWrapper<T> select(SFunction<T, ?> column) {
        selectColumns.add(resolveColumn(column));
        return this;
    }

    /**
     * 批量添加 SELECT 列。
     */
    @SafeVarargs
    public final ReactorLambdaQueryWrapper<T> select(SFunction<T, ?>... columns) {
        for (SFunction<T, ?> c : columns) {
            select(c);
        }
        return this;
    }

    /**
     * 以字符串形式添加 SELECT 列。
     */
    public ReactorLambdaQueryWrapper<T> select(String... columns) {
        selectColumns.addAll(List.of(columns));
        return this;
    }

    /**
     * 添加 GROUP BY 列。
     */
    public ReactorLambdaQueryWrapper<T> groupBy(SFunction<T, ?> column) {
        this.groupByColumn = resolveColumn(column);
        return this;
    }

    // ==================== SQL 构建 ====================

    /**
     * 构建查询 SQL 信息。
     */
    public QuerySql<T> buildSql() {
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        buildWhere(where, params);
        return new QuerySql<>(entityClass, selectColumns, where.toString(), params, groupByColumn, getOrderBys());
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
        return Mono.fromCallable(() -> {
            List<T> all = doList();
            int from = (pageNum - 1) * pageSize;
            int to = Math.min(from + pageSize, all.size());
            if (from >= all.size()) {
                return new Page<T>(pageNum, pageSize, all.size(), List.of());
            }
            return new Page<T>(pageNum, pageSize, all.size(), all.subList(from, to));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 同步执行查询（内部使用，供响应式方法在 boundedElastic 线程调用）。
     */
    private List<T> doList() {
        QuerySql<T> sql = buildSql();
        String tableName = com.chua.datasource.support.engine.AbstractEngine.resolveTableName(entityClass);
        StringBuilder fullSql = new StringBuilder("SELECT ");
        if (sql.hasSelect()) {
            fullSql.append(String.join(", ", sql.selectColumns()));
        } else {
            fullSql.append("*");
        }
        fullSql.append(" FROM ").append(tableName);
        if (sql.hasWhere()) {
            fullSql.append(" WHERE ").append(sql.whereClause());
        }
        if (sql.hasGroupBy()) {
            fullSql.append(" GROUP BY ").append(sql.groupByColumn());
        }
        if (sql.hasOrderBy()) {
            fullSql.append(" ORDER BY ").append(String.join(", ", sql.orderBys()));
        }
        return engine.getExecutor().query(fullSql.toString(), entityClass, sql.params().toArray());
    }

    // ==================== 内部实现 ====================

    @Override
    protected ReactorLambdaQueryWrapper<T> newInstance() {
        return new ReactorLambdaQueryWrapper<>(engine, entityClass);
    }

    @Override
    protected String resolveColumn(SFunction<T, ?> column) {
        return LambdaUtils.resolveObject(column);
    }

    /**
     * 构建 WHERE 子句和参数列表。
     */
    protected void buildWhere(StringBuilder sb, List<Object> params) {
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            renderCondition(sb, params, conditions.get(i));
        }
    }

    /**
     * 渲染单个条件为 SQL 片段。
     */
    protected void renderCondition(StringBuilder sb, List<Object> params, Condition c) {
        if (c.isNested()) {
            sb.append("(");
            for (int i = 0; i < c.getNested().size(); i++) {
                if (i > 0) {
                    sb.append(" ").append(c.getNestedOperator()).append(" ");
                }
                renderCondition(sb, params, c.getNested().get(i));
            }
            sb.append(")");
            return;
        }
        String col = c.getColumnName();
        if (col == null) {
            col = "?";
        }
        sb.append(col);
        switch (c.getOperator()) {
            case "IS NULL":
            case "IS NOT NULL":
                sb.append(" ").append(c.getOperator());
                break;
            case "IN":
            case "NOT IN":
                sb.append(" ").append(c.getOperator()).append(" (");
                Collection<?> vals = (Collection<?>) c.getValue();
                Iterator<?> it = vals.iterator();
                for (int i = 0; i < vals.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append("?");
                    params.add(it.next());
                }
                sb.append(")");
                break;
            case "BETWEEN":
                Object[] range = (Object[]) c.getValue();
                sb.append(" BETWEEN ? AND ?");
                params.add(range[0]);
                params.add(range[1]);
                break;
            default:
                sb.append(" ").append(c.getOperator()).append(" ?");
                params.add(c.getValue());
                break;
        }
    }
}