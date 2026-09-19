package com.chua.datasource.support.dialect;
import java.util.Properties;
/** duckdb 0.6 方言。 */
public class Duckdb06Dialect extends SqlDialect {
    public static final String VERSION = "DuckDB 0.6"; // 版本
    /**
    * Duckdb06Dialect。
    */
    public Duckdb06Dialect() { super("duckdb06"); }
    /**
     * Duckdb06Dialect。
     * @param properties 属性
     */
    public Duckdb06Dialect(Properties properties) { super("duckdb06", properties); }
}
