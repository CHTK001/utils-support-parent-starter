package com.chua.common.support.lang.datasource.engine.wrapper;

import java.util.List;

/**
* 删除 SQL 信息记录，包含构建删除语句所需的所有结构化数据。
*
* @author CH
* @since 2024/12/12
 */
public record DeleteSql<T>(
        Class<T> entityClass,
        String whereClause,
        List<Object> params
) {

    /**
    * 判断是否存在有效的 WHERE 子句。
    *
    * @return 如果存在 WHERE 子句则返回 true，否则返回 false
    */
    public boolean hasWhere() {
        return whereClause != null && !whereClause.isEmpty();
    }
}
