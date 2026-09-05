package com.chua.datasource.support.dialect;
import java.util.Properties;
/** SQL Server 2008 方言。 */
public class SqlServer2008Dialect extends SqlDialect {
    public static final String VERSION = "SQL Server 2008";
    public SqlServer2008Dialect() { super("sqlserver2008"); }
    public SqlServer2008Dialect(Properties properties) { super("sqlserver2008", properties); }
}
