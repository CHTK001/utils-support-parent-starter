package com.chua.datasource.support.dialect;
import java.util.Properties;
/**
 * H2 1.x 方言。
*/
public class H2V1Dialect extends SqlDialect {
    public static final String VERSION = "H2 1.x"; // 版本
    /**
     * H2V1Dialect。
     */
    public H2V1Dialect() { super("h2v1"); }
    /**
     * H2V1Dialect。
     * @param properties 属性
     */
    public H2V1Dialect(Properties properties) { super("h2v1", properties); }
}
