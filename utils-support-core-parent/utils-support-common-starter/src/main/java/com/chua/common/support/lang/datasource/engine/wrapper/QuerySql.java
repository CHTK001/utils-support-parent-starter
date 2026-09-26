package com.chua.common.support.lang.datasource.engine.wrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 查询 SQL 信息记录，包含构建查询所需的所有结构化数据。
 * <p>
 * 除基础的 SELECT 列 / WHERE / GROUP BY / ORDER BY / 分页外，
 * 还携带 JOIN 关联子句列表与 HAVING 分组过滤条件，由 SQL 引擎
 * （如 {@code JdbcEngine}）渲染为完整 SELECT 语句下推到数据库执行。
 * </p>
 *
 * @param entityClass  实体类类型
 * @param selectColumns SELECT 投影列列表（含聚合表达式，如 {@code SUM(amount) AS total}）
 * @param whereClause   WHERE 条件片段（{@code ?} 占位符，不含 WHERE 关键字）
 * @param params        WHERE 参数列表（与占位符顺序一致）
 * @param groupByColumn GROUP BY 列（多列时为逗号分隔串），null 表示无分组
 * @param orderBys      ORDER BY 列表，每项格式为 {@code 列名 ASC/DESC}
 * @param limit         返回行数上限，0 表示不限制
 * @param offset        偏移行数，0 表示不偏移
 * @param joins         JOIN 关联子句列表，空列表表示无关联
 * @param havingClause  HAVING 条件片段（{@code ?} 占位符，不含 HAVING 关键字），null 表示无
 * @param havingParams  HAVING 参数列表（与占位符顺序一致，绑定在 WHERE 参数之后）
 * @param <T>           实体类型
 * @author CH
 * @since 4.0.0.42
 */
public record QuerySql<T>(
        Class<T> entityClass,
        List<String> selectColumns,
        String whereClause,
        List<Object> params,
        String groupByColumn,
        List<String> orderBys,
        int limit,
        int offset,
        List<JoinClause> joins,
        String havingClause,
        List<Object> havingParams
) {

    /**
     * 规范构造器：实体类型必填，五个列表组件做防御性拷贝。
     *
     * <p>value class 前置条件——空值敌对，且集合组件必须深不可变。
     * {@code selectColumns} / {@code orderBys} / {@code joins} 分别由
     * {@link #hasSelect()}、{@link #hasOrderBy()}、{@link #hasJoins()} 显式判空，
     * 允许为 null；{@code params} 与 {@code havingParams} 是 SQL 绑定值，
     * 既可能整体为 null，也可能含 null 绑定值。因此五个列表均保留 null 语义
     * 并采用可空安全写法。</p>
     *
     * @param entityClass   实体类类型，不允许为 null
     * @param selectColumns SELECT 投影列列表，可为 null
     * @param whereClause   WHERE 条件片段，可为 null
     * @param params        WHERE 参数列表，可为 null
     * @param groupByColumn GROUP BY 列，可为 null
     * @param orderBys      ORDER BY 列表，可为 null
     * @param limit         返回行数上限
     * @param offset        偏移行数
     * @param joins         JOIN 关联子句列表，可为 null
     * @param havingClause  HAVING 条件片段，可为 null
     * @param havingParams  HAVING 参数列表，可为 null
     * @param <T>           实体类型
     */
    public QuerySql {
        Objects.requireNonNull(entityClass, "entityClass 不能为 null");
        selectColumns = copyList(selectColumns);
        params = copyList(params);
        orderBys = copyList(orderBys);
        joins = copyList(joins);
        havingParams = copyList(havingParams);
    }

    /**
     * 复制列表组件：只做外层防御性拷贝，不改变元素本身，允许元素为 null。
     *
     * @param values 列表组件，可为 null
     * @param <E>    元素类型
     * @return 不可变列表，入参为 null 时返回 null
     */
    private static <E> List<E> copyList(List<E> values) {
        if (values == null) {
            return null;
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * 兼容构造器：不含 JOIN 与 HAVING 的旧版查询。
     *
     * @param entityClass  实体类类型
     * @param selectColumns SELECT 投影列列表
     * @param whereClause   WHERE 条件片段
     * @param params        WHERE 参数列表
     * @param groupByColumn GROUP BY 列
     * @param orderBys      ORDER BY 列表
     * @param limit         返回行数上限
     * @param offset        偏移行数
     */
    public QuerySql(Class<T> entityClass, List<String> selectColumns, String whereClause,
                    List<Object> params, String groupByColumn, List<String> orderBys, int limit, int offset) {
        this(entityClass, selectColumns, whereClause, params, groupByColumn, orderBys,
                limit, offset, List.of(), null, List.of());
    }

    /**
     * 兼容构造器：不含分页、JOIN 与 HAVING 的基础查询。
     *
     * @param entityClass  实体类类型
     * @param selectColumns SELECT 投影列列表
     * @param whereClause   WHERE 条件片段
     * @param params        WHERE 参数列表
     * @param groupByColumn GROUP BY 列
     * @param orderBys      ORDER BY 列表
     */
    public QuerySql(Class<T> entityClass, List<String> selectColumns, String whereClause,
                    List<Object> params, String groupByColumn, List<String> orderBys) {
        this(entityClass, selectColumns, whereClause, params, groupByColumn, orderBys, 0, 0);
    }

    /**
     * 判断是否包含 SELECT 投影列。
     *
     * @return true 表示设置了投影列
     */
    public boolean hasSelect() {
        if (selectColumns == null) {
            return false;
        }
        return !selectColumns.isEmpty();
    }

    /**
     * 判断是否包含 WHERE 条件。
     *
     * @return true 表示设置了 WHERE 条件
     */
    public boolean hasWhere() {
        if (whereClause == null) {
            return false;
        }
        return !whereClause.isEmpty();
    }

    /**
     * 判断是否包含 GROUP BY 子句。
     *
     * @return true 表示设置了分组列
     */
    public boolean hasGroupBy() {
        return groupByColumn != null;
    }

    /**
     * 判断是否包含 ORDER BY 子句。
     *
     * @return true 表示设置了排序列
     */
    public boolean hasOrderBy() {
        if (orderBys == null) {
            return false;
        }
        return !orderBys.isEmpty();
    }

    /**
     * 判断是否设置了 LIMIT。
     *
     * @return true 表示设置了行数上限
     */
    public boolean hasLimit() {
        return limit > 0;
    }

    /**
     * 判断是否设置了 OFFSET。
     *
     * @return true 表示设置了偏移行数
     */
    public boolean hasOffset() {
        return offset > 0;
    }

    /**
     * 判断是否包含 JOIN 关联子句。
     *
     * @return true 表示设置了表关联
     */
    public boolean hasJoins() {
        if (joins == null) {
            return false;
        }
        return !joins.isEmpty();
    }

    /**
     * 判断是否包含 HAVING 子句。
     *
     * @return true 表示设置了分组过滤条件
     */
    public boolean hasHaving() {
        if (havingClause == null) {
            return false;
        }
        return !havingClause.isEmpty();
    }
}
