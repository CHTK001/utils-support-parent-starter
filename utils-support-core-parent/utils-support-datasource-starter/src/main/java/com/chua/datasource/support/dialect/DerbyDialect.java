package com.chua.datasource.support.dialect;
import java.util.Properties;
/** Apache Derby 10.15+ 方言（标准 OFFSET...FETCH 分页）。 */
public class DerbyDialect extends SqlDialect {
    public static final String VERSION = "Apache Derby 10.15+";
    public DerbyDialect() { super("derby"); }
    public DerbyDialect(Properties properties) { super("derby", properties); }
}
