package com.chua.datasource.support.dialect;
import java.util.Properties;
/** duckdb 方言。 */
public class DuckdbDialect extends SqlDialect {
    public static final String VERSION = "DuckDB 0.8+"; // 版本
    /**
    * duckdbdialect。
     */
    public DuckdbDialect() { super("duckdb"); }
    /**
    * duckdbdialect。
    * @param properties 属性
     */
    public DuckdbDialect(Properties properties) { super("duckdb", properties); }
}
