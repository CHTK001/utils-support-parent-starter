package com.chua.common.support.lang.datasource.engine.wrapper;

import java.util.List;

/**
* 更新 SQL 信息记录，包含构建更新语句所需的所有结构化数据。
*
* @author CH
* @since 2024/12/12
 */
public record UpdateSql<T>(
        Class<T> entityClass,
        String setClause,
        String whereClause,
        List<Object> params
) {

    /**
    * 检查是否存在 SET 子句。
    *
    * @return 如果存在非空的 SET 子句则返回 true，否则返回 false
    */
    public boolean hasSet() {
        if (setClause == null || setClause.isEmpty()) {
            return false;
        }
        return true;
    }

    /**
    * 检查是否存在 WHERE 子句。
    *
    * @return 如果存在非空的 WHERE 子句则返回 true，否则返回 false
    */
    public boolean hasWhere() {
        if (whereClause == null || whereClause.isEmpty()) {
            return false;
        }
        return true;
    }
}
