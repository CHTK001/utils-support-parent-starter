package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;
/**
 * @author CH
 */

public class Duckdb06Dialect extends AbstractDialect {

    public static final String VERSION = "DuckDB 0.6";

    @Override
    public String protocol() {
        return "duckdb06";
    }

    @Override
    public String driver() {
        return "org.duckdb.DuckDBDriver";
    }

    @Override
    public String url() {
        return "jdbc:duckdb:<DATABASE>";
    }

    @Override
    public char openQuote() {
        return '"';
    }

    @Override
    public char closeQuote() {
        return '"';
    }

    @Override
    public String processSql(String sql, Pagination pagination) {
        return sql + " LIMIT " + pagination.getLimit() + " OFFSET " + pagination.getOffset();
    }

    @Override
    public String getTypeName(int jdbcType, long length, int precision, int scale) {
        return switch (jdbcType) {
            case java.sql.Types.INTEGER -> "INTEGER";
            case java.sql.Types.BIGINT -> "BIGINT";
            case java.sql.Types.SMALLINT -> "SMALLINT";
            case java.sql.Types.TINYINT -> "TINYINT";
            case java.sql.Types.VARCHAR -> "VARCHAR";
            case java.sql.Types.CHAR -> "CHAR";
            case java.sql.Types.DECIMAL -> "DECIMAL(" + precision + "," + scale + ")";
            case java.sql.Types.DOUBLE -> "DOUBLE";
            case java.sql.Types.FLOAT -> "FLOAT";
            case java.sql.Types.BOOLEAN -> "BOOLEAN";
            case java.sql.Types.TIMESTAMP -> "TIMESTAMP";
            case java.sql.Types.DATE -> "DATE";
            case java.sql.Types.TIME -> "TIME";
            case java.sql.Types.CLOB -> "VARCHAR";
            case java.sql.Types.BLOB -> "BLOB";
            case java.sql.Types.LONGVARCHAR -> "VARCHAR";
            case java.sql.Types.LONGNVARCHAR -> "VARCHAR";
            default -> "VARCHAR";
        };
    }

    @Override
    public String getAlterColumnString() {
        return "ALTER COLUMN";
    }

    @Override
    public String getCurrentTimestampSelectString() {
        return "SELECT CURRENT_TIMESTAMP";
    }
}
