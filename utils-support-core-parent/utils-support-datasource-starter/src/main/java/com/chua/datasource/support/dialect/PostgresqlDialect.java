package com.chua.datasource.support.dialect;

import java.util.Properties;

/**
 * PostgreSQL 14+ 方言（兼容 12/13）。
 * <p>配置从 {@code META-INF/dialect-env/postgresql.env} 加载。</p>
 */
public class PostgresqlDialect extends SqlDialect {
    public static final String VERSION = "PostgreSQL 14+ (兼容 12/13)";

    public PostgresqlDialect() { super("postgresql"); }
    public PostgresqlDialect(Properties properties) { super("postgresql", properties); }
}
