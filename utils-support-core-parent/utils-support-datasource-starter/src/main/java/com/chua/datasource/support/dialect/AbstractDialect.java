package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Pagination;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 方言抽象基类。
 * <p>
 * 提供默认的分页 SQL 生成（LIMIT/OFFSET 语法）和默认类型映射。
 * 所有数据库特有字符串（引号、关键字、DDL片段、SQL模板）均通过 {@link #config(String, String)}
 * 从 {@link #properties} 读取，properties 支持通过环境变量 / application.properties 注入。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractDialect implements Dialect {

    /**
     * 方言配置属性，可通过 Spring {@code application.properties}、环境变量或构造参数注入。
     * <p>支持通过此属性集覆盖 {@link #driver()}、{@link #url()} 及其他 SQL 片段。</p>
     */
    protected Properties properties;

    /** 内存中的默认值缓存，避免重复从 properties 读取 */
    private final Map<String, String> configCache = new HashMap<>();

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
        return config("driver", null);
    }

    @Override
    /** Url */
    public String url() {
        return config("url", null);
    }

    /**
     * 根据 key 从 properties 中读取配置值，找不到时返回 {@code defaultValue}。
     * <p>读取结果会被缓存，避免重复 I/O。</p>
     *
     * <pre>{@code
     * // application.properties 示例：
     * dialect.mysql.quote-open=`
     * dialect.mysql.quote-close=`
     * dialect.mysql.engine-keyword=ENGINE
     * }</pre>
     *
     * @param key          配置键（不带前缀，前缀由子类提供）
     * @param defaultValue 默认值
     * @return 配置值，未配置时返回默认值
     */
    protected String config(String key, String defaultValue) {
        String cacheKey = key;
        if (configCache.containsKey(cacheKey)) {
            return configCache.get(cacheKey);
        }
        if (properties != null) {
            String val = properties.getProperty(key);
            if (val != null) {
                configCache.put(cacheKey, val);
                return val;
            }
        }
        configCache.put(cacheKey, defaultValue);
        return defaultValue;
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
     * 设置方言配置属性。
     * <p>调用后所有 {@link #config(String, String)} 将优先返回属性中的值，同时清空缓存。</p>
     *
     * @param properties 属性集合
     * @return this
     */
    public AbstractDialect withProperties(Properties properties) {
        this.properties = properties;
        this.configCache.clear();
        return this;
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
