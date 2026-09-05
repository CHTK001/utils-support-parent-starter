package com.chua.datasource.support.dialect;

import java.util.Properties;

/**
 * SQL Server 2019+ 方言（兼容 2016/2017）。
 * <p>配置从 {@code META-INF/dialect-env/sqlserver.env} 加载。</p>
 */
public class SqlServerDialect extends SqlDialect {
    public static final String VERSION = "SQL Server 2019+ (兼容 2016/2017)";

    public SqlServerDialect() { super("sqlserver"); }
    public SqlServerDialect(Properties properties) { super("sqlserver", properties); }
}
