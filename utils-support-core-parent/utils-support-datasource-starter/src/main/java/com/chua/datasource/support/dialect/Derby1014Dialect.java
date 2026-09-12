package com.chua.datasource.support.dialect;
import java.util.Properties;
/** Derby 10.14 方言（ROW_数字 分页）。 */
public class Derby1014Dialect extends SqlDialect {
    public static final String VERSION = "Apache Derby 10.14"; // 版本
    /**
    * Derby1014Dialect。
     */
    public Derby1014Dialect() { super("derby1014"); }
    /**
    * Derby1014Dialect。
    * @param properties 属性
     */
    public Derby1014Dialect(Properties properties) { super("derby1014", properties); }
}
