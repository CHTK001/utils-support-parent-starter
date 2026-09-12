package com.chua.datasource.support.dialect;

import java.util.Properties;

/**
   * SQL 服务端 2019+ 方言（兼容 2016/2017）。
 * <p>配置从 {@code META-INF/dialect-env/sqlserver.env} 加载。</p>
 * @author CH
 * @since 4.0.0
 */
public class SqlServerDialect extends SqlDialect {
    public static final String VERSION = "SQL Server 2019+ (兼容 2016/2017)"; // 版本

    /**
      * SQL服务端dialect。
     */
    public SqlServerDialect() { super("sqlserver"); }
    /**
      * SQL服务端dialect。
     * @param properties 属性
     */
    public SqlServerDialect(Properties properties) { super("sqlserver", properties); }
}
