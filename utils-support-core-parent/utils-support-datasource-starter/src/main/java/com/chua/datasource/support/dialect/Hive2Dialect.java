package com.chua.datasource.support.dialect;
import java.util.Properties;
/** Hive 2.x 方言。 */
public class Hive2Dialect extends SqlDialect {
    public static final String VERSION = "Apache Hive 2.x";
    public Hive2Dialect() { super("hive2"); }
    public Hive2Dialect(Properties properties) { super("hive2", properties); }
}
