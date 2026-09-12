package com.chua.datasource.support.dialect;
import java.util.Properties;
/** Oracle 12C 方言（偏移量...获取 分页）。 */
public class Oracle12cDialect extends SqlDialect {
    public static final String VERSION = "Oracle 12c"; // 版本
    /**
      * Oracle12cdialect。
     */
    public Oracle12cDialect() { super("oracle12c"); }
    /**
      * Oracle12cdialect。
     * @param properties 属性
     */
    public Oracle12cDialect(Properties properties) { super("oracle12c", properties); }
}
