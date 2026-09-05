package com.chua.datasource.support.dialect;
import java.util.Properties;
/** Oracle 11g 方言（ROWNUM 分页，无 OFFSET...FETCH）。 */
public class Oracle11gDialect extends SqlDialect {
    public static final String VERSION = "Oracle 11g";
    public Oracle11gDialect() { super("oracle11g"); }
    public Oracle11gDialect(Properties properties) { super("oracle11g", properties); }
}
