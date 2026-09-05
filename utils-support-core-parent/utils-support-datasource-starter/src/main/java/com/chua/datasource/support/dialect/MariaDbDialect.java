package com.chua.datasource.support.dialect;
import java.util.Properties;
/** MariaDB 10.6+ 方言（兼容 10.3/10.5）。 */
public class MariaDbDialect extends SqlDialect {
    public static final String VERSION = "MariaDB 10.6+ (兼容 10.3/10.5)";
    public MariaDbDialect() { super("mariadb"); }
    public MariaDbDialect(Properties properties) { super("mariadb", properties); }
}
