package com.chua.common.support.lang.datasource.engine.wrapper;

import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * 查询 SQL 信息记录，包含构建查询所需的所有结构化数据。
 *
 * @author CH
 * @since 2024/12/12
 */
@NullUnmarked
public record QuerySql<T>(
        Class<T> entityClass,
        List<String> selectColumns,
        String whereClause,
        List<Object> params,
        String groupByColumn,
        List<String> orderBys
) {

    /**
     * 判断是否包含 SELECT 列
     *
     * @return true 如果包含 SELECT 列，否则 false
     */
    public boolean hasSelect() {
        if (selectColumns == null) {
            return false;
        }
        return !selectColumns.isEmpty();
    }

    /**
     * 判断是否包含 WHERE 条件
     *
     * @return true 如果包含 WHERE 条件，否则 false
     */
    public boolean hasWhere() {
        if (whereClause == null) {
            return false;
        }
        return !whereClause.isEmpty();
    }

    /**
     * 判断是否包含 GROUP BY 子句
     *
     * @return true 如果包含 GROUP BY 子句，否则 false
     */
    public boolean hasGroupBy() {
        if (groupByColumn == null) {
            return false;
        }
        return true;
    }

    /**
     * 判断是否包含 ORDER BY 子句
     *
     * @return true 如果包含 ORDER BY 子句，否则 false
     */
    public boolean hasOrderBy() {
        if (orderBys == null) {
            return false;
        }
        return !orderBys.isEmpty();
    }
}
