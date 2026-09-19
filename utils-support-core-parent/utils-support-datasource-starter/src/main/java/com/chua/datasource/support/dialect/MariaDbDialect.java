package com.chua.datasource.support.dialect;
import java.util.Properties;
/** mariadb 10.6+ 方言（兼容 10.3/10.5）。 */
public class MariaDbDialect extends SqlDialect {
    public static final String VERSION = "MariaDB 10.6+ (兼容 10.3/10.5)"; // 版本
    /**
     * mariadbdialect。
     */
    public MariaDbDialect() { super("mariadb"); }
    /**
     * mariadbdialect。
     * @param properties 属性
     */
    public MariaDbDialect(Properties properties) { super("mariadb", properties); }
}
