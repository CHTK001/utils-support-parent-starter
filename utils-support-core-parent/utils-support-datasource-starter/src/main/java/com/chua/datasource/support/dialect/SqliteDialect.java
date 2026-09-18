package com.chua.datasource.support.dialect;
import java.util.Properties;
/** sqlite 3.x 方言。 */
public class SqliteDialect extends SqlDialect {
    public static final String VERSION = "SQLite 3.x"; // 版本
    /**
    * sqlitedialect。
    */
    public SqliteDialect() { super("sqlite"); }
    /**
    * sqlitedialect。
    * @param properties 属性
    */
    public SqliteDialect(Properties properties) { super("sqlite", properties); }
}
