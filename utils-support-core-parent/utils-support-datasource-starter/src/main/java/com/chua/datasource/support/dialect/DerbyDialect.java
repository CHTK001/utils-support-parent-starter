package com.chua.datasource.support.dialect;
import java.util.Properties;
/** Apache Derby 10.15+ 方言（标准 偏移量...获取 分页）。 */
public class DerbyDialect extends SqlDialect {
    public static final String VERSION = "Apache Derby 10.15+"; // 版本
    /**
    * derbydialect。
    */
    public DerbyDialect() { super("derby"); }
    /**
     * derbydialect。
     * @param properties 属性
     */
    public DerbyDialect(Properties properties) { super("derby", properties); }
}
