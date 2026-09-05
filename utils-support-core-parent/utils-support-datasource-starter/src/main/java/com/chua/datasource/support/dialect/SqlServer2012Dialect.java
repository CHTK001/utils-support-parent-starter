package com.chua.datasource.support.dialect;
import java.util.Properties;
/** SQL Server 2012 方言。 */
public class SqlServer2012Dialect extends SqlDialect {
    public static final String VERSION = "SQL Server 2012";
    public SqlServer2012Dialect() { super("sqlserver2012"); }
    public SqlServer2012Dialect(Properties properties) { super("sqlserver2012", properties); }
}
