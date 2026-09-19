package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Pagination;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 方言抽象基类。
 * <p>
 * 提供默认的分页 SQL 生成（限制/偏移量 语法）和默认类型映射。
 * 所有数据库特有字符串（引号、关键字、DDL片段、SQL模板、JDBC类型映射）
 * 均从 {@code META-INF/dialect-env/{protocol}.env} 资源文件加载，
 * 再由外部传入的 {@link #properties} 覆盖。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractDialect implements Dialect {

    /**
     * 方言配置属性，可通过 Spring {@code application.properties}、环境变量或构造参数注入。
     * <p>外部传入的 properties 优先级高于内置 .env 文件。</p>
     */
    protected Properties properties;

    /**
     * 内存中的默认值缓存，避免重复从 属性 读取
    */
    private final Map<String, String> configCache = new HashMap<>();

    /**
     * 从类路径 {@code META-INF/dialect-env/{className-lowercase}.env} 加载默认配置。
     * <p>例如 {@code MysqlDialect} → {@code mysql.env}，{@code PostgresqlDialect} → {@code postgresql.env}。</p>
     */
    protected AbstractDialect() {
        this.properties = loadDefaultEnv();
    }

    /**
     * 从类路径加载方言默认配置文件。
     *
     * @return 加载后的 属性，文件不存在时返回空 属性
     */
    protected Properties loadDefaultEnv() {
        String simpleName = getClass().getSimpleName().toLowerCase();
        String resourceName = "META-INF/dialect-env/" + simpleName + ".env";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                return new Properties();
            }
            Properties props = new Properties();
            props.load(new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8)));
            return props;
        } catch (IOException e) {
            return new Properties();
        }
    }

    @Override
    /**
     * 支持限制
    */
    public boolean supportsLimit() {
        return true;
    }

    @Override
    /**
     * 处理SQL
    */
    public String processSql(String sql, Pagination pagination) {
        return sql + " LIMIT " + pagination.getLimit() + " OFFSET " + pagination.getOffset();
    }

    @Override
    /**
     * 获取类型名称
    */
    public String getTypeName(int jdbcType, long length, int precision, int scale) {
 // 1. 优先读外部 属性（application.属性 等）
        String configured = config("type." + jdbcTypeName(jdbcType), null);
        if (configured != null) {
            return configured;
        }
        // 2. 回退到通用默认值
        return "VARCHAR";
    }

    /**
     * 将 JDBC 类型码转为常量名字符串，用于 属性 键 查找。
     * <p>例如 {@code java.sql.Types.VARCHAR} → {@code "VARCHAR"}，未知类型 → {@code "UNKNOWN"}。</p>
     * @param jdbcType JDBC类型
     * @return jdbc类型名称的结果
     */
    protected static String jdbcTypeName(int jdbcType) {
        return switch (jdbcType) {
            case java.sql.Types.BIGINT       -> "BIGINT";
            case java.sql.Types.BINARY       -> "BINARY";
            case java.sql.Types.BIT          -> "BIT";
            case java.sql.Types.BLOB         -> "BLOB";
            case java.sql.Types.BOOLEAN      -> "BOOLEAN";
            case java.sql.Types.CHAR         -> "CHAR";
            case java.sql.Types.CLOB         -> "CLOB";
            case java.sql.Types.DATALINK     -> "DATALINK";
            case java.sql.Types.DATE         -> "DATE";
            case java.sql.Types.DECIMAL      -> "DECIMAL";
            case java.sql.Types.DOUBLE       -> "DOUBLE";
            case java.sql.Types.FLOAT        -> "FLOAT";
            case java.sql.Types.INTEGER      -> "INTEGER";
            case java.sql.Types.JAVA_OBJECT  -> "JAVA_OBJECT";
            case java.sql.Types.LONGNVARCHAR -> "LONGNVARCHAR";
            case java.sql.Types.LONGVARCHAR-> "LONGVARCHAR";
            case java.sql.Types.NCHAR        -> "NCHAR";
            case java.sql.Types.NCLOB        -> "NCLOB";
            case java.sql.Types.NUMERIC      -> "NUMERIC";
            case java.sql.Types.NVARCHAR     -> "NVARCHAR";
            case java.sql.Types.OTHER        -> "OTHER";
            case java.sql.Types.REAL         -> "REAL";
            case java.sql.Types.REF          -> "REF";
            case java.sql.Types.ROWID        -> "ROWID";
            case java.sql.Types.SMALLINT     -> "SMALLINT";
            case java.sql.Types.SQLXML       -> "SQLXML";
            case java.sql.Types.STRUCT       -> "STRUCT";
            case java.sql.Types.TIME         -> "TIME";
            case java.sql.Types.TIMESTAMP    -> "TIMESTAMP";
            case java.sql.Types.TINYINT      -> "TINYINT";
            case java.sql.Types.VARBINARY    -> "VARBINARY";
            case java.sql.Types.VARCHAR      -> "VARCHAR";
            default                          -> "UNKNOWN";
        };
    }

    @Override
    /**
     * Driver
    */
    public String driver() {
        return config("driver", null);
    }

    @Override
    /**
     * Url
    */
    public String url() {
        return config("url", null);
    }

    /**
     * 根据 键 从 属性 中读取配置值，找不到时返回 {@code defaultValue}。
     * <p>读取顺序：外部注入的 properties → 内置 .env 文件 → defaultValue。</p>
     * <p>读取结果会被缓存，避免重复 I/O。</p>
     *
     * <pre>{@code
     * // application.properties 示例（覆盖默认值）：
     * dialect.mysql.quote-open=[
     * dialect.mysql.type.VARCHAR=VARCHAR(1000)
     * }</pre>   * }</pre>
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值
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
     * 获取方言配置属性（合并了内置 .env + 外部注入）。
     *
     * @return 属性集合
     */
    public Properties getProperties() {
        return properties;
    }

    /**
     * 设置方言配置属性，与内置 .env 合并。
     * <p>外部 properties 优先级高于内置 .env，同时清空缓存。</p>
     *
     * @param properties 外部属性集合
     * @return this
     */
    public AbstractDialect withProperties(Properties properties) {
 // 合并：先存 .env 默认值，再被外部 属性 覆盖
        Properties merged = new Properties(this.properties);
        if (properties != null) {
            merged.putAll(properties);
        }
        this.properties = merged;
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
