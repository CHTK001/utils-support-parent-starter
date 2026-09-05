package com.chua.datasource.support.dialect;
import java.util.Properties;
/** DuckDB 0.6 方言。 */
public class Duckdb06Dialect extends SqlDialect {
    public static final String VERSION = "DuckDB 0.6";
    public Duckdb06Dialect() { super("duckdb06"); }
    public Duckdb06Dialect(Properties properties) { super("duckdb06", properties); }
}
