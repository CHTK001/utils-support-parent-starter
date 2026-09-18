package com.chua.datasource.support.dialect;
import java.util.Properties;
/** SQL 服务端 2008 方言。 */
public class SqlServer2008Dialect extends SqlDialect {
    public static final String VERSION = "SQL Server 2008"; // 版本
    /**
    * sql服务端2008Dialect。
    */
    public SqlServer2008Dialect() { super("sqlserver2008"); }
    /**
    * sql服务端2008Dialect。
    * @param properties 属性
    */
    public SqlServer2008Dialect(Properties properties) { super("sqlserver2008", properties); }
}
