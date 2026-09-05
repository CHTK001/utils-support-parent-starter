package com.chua.datasource.support.dialect;
import java.util.Properties;
/** Derby 10.14 方言（ROW_NUMBER 分页）。 */
public class Derby1014Dialect extends SqlDialect {
    public static final String VERSION = "Apache Derby 10.14";
    public Derby1014Dialect() { super("derby1014"); }
    public Derby1014Dialect(Properties properties) { super("derby1014", properties); }
}
