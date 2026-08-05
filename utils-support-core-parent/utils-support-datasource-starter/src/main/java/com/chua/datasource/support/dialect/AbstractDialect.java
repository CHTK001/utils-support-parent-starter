package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Pagination;

/**
 * 方言抽象基类。
 * <p>
 * 提供默认的分页 SQL 生成（LIMIT/OFFSET 语法）和默认类型映射。
 * 触发器/存储过程的获取 SQL 由各具体方言实现 {@link Dialect} 接口的
 * {@code getTriggerListSql} / {@code getProcedureListSql} 系列方法提供，
 * 执行与结果解析由 {@code JdbcEngine} 负责。
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

    /**
     * 转义 SQL 字符串中的单引号。
     *
     * @param value 原始值
     * @return 转义后的值，null 原样返回
     */
    protected static String escape(String value) {
        return value == null ? null : value.replace("'", "''");
    }
}
