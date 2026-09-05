package com.chua.datasource.support.dialect;
import java.util.Properties;
/** Apache Hive 3.x 方言。 */
public class HiveDialect extends SqlDialect {
    public static final String VERSION = "Apache Hive 3.x";
    public HiveDialect() { super("hive"); }
    public HiveDialect(Properties properties) { super("hive", properties); }
}
