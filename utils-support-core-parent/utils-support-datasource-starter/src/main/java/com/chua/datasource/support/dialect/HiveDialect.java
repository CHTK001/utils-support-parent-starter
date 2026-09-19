package com.chua.datasource.support.dialect;
import java.util.Properties;
/** Apache Hive 3.x 方言。 */
public class HiveDialect extends SqlDialect {
    public static final String VERSION = "Apache Hive 3.x"; // 版本
    /**
     * Hivedialect。
     */
    public HiveDialect() { super("hive"); }
    /**
     * Hivedialect。
     * @param properties 属性
     */
    public HiveDialect(Properties properties) { super("hive", properties); }
}
