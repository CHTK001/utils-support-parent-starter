package com.chua.datasource.support.dialect;

import java.util.Properties;

/**
 * PostgreSQL 14+ 方言（兼容 12/13）。
 * <p>配置从 {@code META-INF/dialect-env/postgresql.env} 加载。</p>
 * @author CH
 * @since 4.0.0
 */
public class PostgresqlDialect extends SqlDialect {
    public static final String VERSION = "PostgreSQL 14+ (兼容 12/13)"; // 版本

    /**
      * postgresqldialect。
     */
    public PostgresqlDialect() { super("postgresql"); }
    /**
      * postgresqldialect。
     * @param properties 属性
     */
    public PostgresqlDialect(Properties properties) { super("postgresql", properties); }
}
