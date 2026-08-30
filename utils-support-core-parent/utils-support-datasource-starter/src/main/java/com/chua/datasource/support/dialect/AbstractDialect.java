package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Pagination;

import java.util.Properties;

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
 * @since 4.0.0.42
 */
public abstract class AbstractDialect implements Dialect {

    /**
     * 方言配置属性。
     * <p>支持通过此属性集覆盖 {@link #driver()}、{@link #url()} 等值。</p>
     */
    protected Properties properties;

    @Override
    /** SupportsLimit */
    public boolean supportsLimit() {
        return true;
    }

    @Override
    /** 处理Sql */
    public String processSql(String sql, Pagination pagination) {
        return sql + " LIMIT " + pagination.getLimit() + " OFFSET " + pagination.getOffset();
    }

    @Override
    /** 获取TypeName */
    public String getTypeName(int jdbcType, long length, int precision, int scale) {
        return "VARCHAR";
    }

    @Override
    /** Driver */
    public String driver() {
        return properties != null
                ? properties.getProperty("driver", null)
                : null;
    }

    @Override
    /** Url */
    public String url() {
        return properties != null
                ? properties.getProperty("url", null)
                : null;
    }

    /**
     * 设置方言配置属性。
     * <p>调用后 {@link #driver()} 和 {@link #url()} 将优先返回属性中的值。</p>
     *
     * @param properties 属性集合
     * @return this
     */
    public AbstractDialect withProperties(Properties properties) {
        this.properties = properties;
        return this;
    }

    /**
     * 获取方言配置属性。
     *
     * @return 属性集合，未设置时返回 null
     */
    public Properties getProperties() {
        return properties;
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
