package com.chua.datasource.support.dialect;
import java.util.Properties;
/** SQLite 3.x 方言。 */
public class SqliteDialect extends SqlDialect {
    public static final String VERSION = "SQLite 3.x";
    public SqliteDialect() { super("sqlite"); }
    public SqliteDialect(Properties properties) { super("sqlite", properties); }
}
