package com.chua.datasource.support.dialect;
import java.util.Properties;
/** SQLite 2.x 方言（极旧版本）。 */
public class Sqlite2Dialect extends SqlDialect {
    public static final String VERSION = "SQLite 2.x";
    public Sqlite2Dialect() { super("sqlite2"); }
    public Sqlite2Dialect(Properties properties) { super("sqlite2", properties); }
}
