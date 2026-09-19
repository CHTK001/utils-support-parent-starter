package com.chua.datasource.support.dialect;
import java.util.Properties;
/** mariadb 10.3 方言。 */
public class MariaDb103Dialect extends SqlDialect {
    public static final String VERSION = "MariaDB 10.3"; // 版本
    /**
    * mariadb103Dialect。
    */
    public MariaDb103Dialect() { super("mariadb103"); }
    /**
     * mariadb103Dialect。
     * @param properties 属性
     */
    public MariaDb103Dialect(Properties properties) { super("mariadb103", properties); }
}
