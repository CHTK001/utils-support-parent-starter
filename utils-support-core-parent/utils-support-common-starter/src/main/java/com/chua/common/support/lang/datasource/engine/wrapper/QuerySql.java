package com.chua.common.support.lang.datasource.engine.wrapper;

import java.util.List;

/**
* 查询 SQL 信息记录，包含构建查询所需的所有结构化数据。
*
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
        int offset
) {

    public QuerySql(Class<T> entityClass, List<String> selectColumns, String whereClause,
                    List<Object> params, String groupByColumn, List<String> orderBys) {
        this(entityClass, selectColumns, whereClause, params, groupByColumn, orderBys, 0, 0);
    }

    /** 判断是否包含 SELECT 列 */
    public boolean hasSelect() {
        if (selectColumns == null) return false;
        return !selectColumns.isEmpty();
    }

    /** 判断是否包含 WHERE 条件 */
    public boolean hasWhere() {
        if (whereClause == null) return false;
        return !whereClause.isEmpty();
    }

    /** 判断是否包含 GROUP BY 子句 */
    public boolean hasGroupBy() {
        return groupByColumn != null;
    }

    /** 判断是否包含 ORDER BY 子句 */
    public boolean hasOrderBy() {
        if (orderBys == null) return false;
        return !orderBys.isEmpty();
    }

    /** 判断是否设置了 LIMIT */
    public boolean hasLimit() {
        return limit > 0;
    }

    /** 判断是否设置了 OFFSET */
    public boolean hasOffset() {
        return offset > 0;
    }
}
