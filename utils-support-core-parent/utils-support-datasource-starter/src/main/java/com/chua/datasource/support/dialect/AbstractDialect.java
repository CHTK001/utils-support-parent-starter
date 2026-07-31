package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Pagination;

/**
 * 方言抽象基类。
 * <p>
 * 提供默认的分页 SQL 生成（LIMIT/OFFSET 语法）和默认类型映射。
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
public abstract class AbstractDialect implements Dialect {

    @Override
    public boolean supportsLimit() {
        return true;
    }

    @Override
    public String processSql(String sql, Pagination pagination) {
        return sql + " LIMIT " + pagination.getLimit() + " OFFSET " + pagination.getOffset();
    }

    @Override
    public String getTypeName(int jdbcType, long length, int precision, int scale) {
        return "VARCHAR";
    }

    @Override
    public String driver() {
        return null;
    }

    @Override
    public String url() {
        return null;
    }
}