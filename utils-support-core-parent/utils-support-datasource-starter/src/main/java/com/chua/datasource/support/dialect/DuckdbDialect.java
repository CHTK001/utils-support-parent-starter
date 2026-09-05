package com.chua.datasource.support.dialect;
import java.util.Properties;
/** DuckDB 方言。 */
public class DuckdbDialect extends SqlDialect {
    public static final String VERSION = "DuckDB 0.8+";
    public DuckdbDialect() { super("duckdb"); }
    public DuckdbDialect(Properties properties) { super("duckdb", properties); }
}
