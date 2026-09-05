package com.chua.datasource.support.dialect;
import java.util.Properties;
/** Oracle 12c 方言（OFFSET...FETCH 分页）。 */
public class Oracle12cDialect extends SqlDialect {
    public static final String VERSION = "Oracle 12c";
    public Oracle12cDialect() { super("oracle12c"); }
    public Oracle12cDialect(Properties properties) { super("oracle12c", properties); }
}
