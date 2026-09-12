package com.chua.datasource.support.dialect;
import java.util.Properties;
/** PostgreSQL 9.x 方言（trigger/procedure SQL 兼容老版本）。 */
public class Postgresql9Dialect extends SqlDialect {
    public static final String VERSION = "PostgreSQL 9.x"; // 版本
    /**
      * PostgreSQL9Dialect。
     */
    public Postgresql9Dialect() { super("postgresql9"); }
    /**
      * PostgreSQL9Dialect。
     * @param properties 属性
     */
    public Postgresql9Dialect(Properties properties) { super("postgresql9", properties); }
}
