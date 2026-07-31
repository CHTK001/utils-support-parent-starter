package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;
/**
 * @author CH
 */

public class ClickHouseDialect extends AbstractDialect {

    public static final String VERSION = "ClickHouse 22.x+";

    @Override
    public String protocol() {
        return "clickhouse";
    }

    @Override
    public String driver() {
        return "com.clickhouse.jdbc.ClickHouseDriver";
    }

    @Override
    public String url() {
        return "jdbc:clickhouse://<IP>:<PORT>/<DATABASE>";
    }

    @Override
    public char openQuote() {
        return '`';
    }

    @Override
    public char closeQuote() {
        return '`';
    }

    @Override
    public String processSql(String sql, Pagination pagination) {
        return sql + " LIMIT " + pagination.getLimit() + " OFFSET " + pagination.getOffset();
    }

    @Override
    public String getTypeName(int jdbcType, long length, int precision, int scale) {
        return switch (jdbcType) {
            case java.sql.Types.INTEGER -> "Int32";
            case java.sql.Types.BIGINT -> "Int64";
            case java.sql.Types.SMALLINT -> "Int16";
            case java.sql.Types.TINYINT -> "Int8";
            case java.sql.Types.VARCHAR -> "String";
            case java.sql.Types.CHAR -> "String";
            case java.sql.Types.DECIMAL -> "Decimal(" + precision + "," + scale + ")";
            case java.sql.Types.DOUBLE -> "Float64";
            case java.sql.Types.FLOAT -> "Float32";
            case java.sql.Types.BOOLEAN -> "UInt8";
            case java.sql.Types.TIMESTAMP -> "DateTime";
            case java.sql.Types.DATE -> "Date";
            case java.sql.Types.TIME -> "DateTime";
            case java.sql.Types.CLOB -> "String";
            case java.sql.Types.BLOB -> "String";
            case java.sql.Types.LONGVARCHAR -> "String";
            case java.sql.Types.LONGNVARCHAR -> "String";
            default -> "String";
        };
    }

    @Override
    public String getAlterColumnString() {
        return "MODIFY COLUMN";
    }

    @Override
    public String getCurrentTimestampSelectString() {
        return "SELECT NOW()";
    }

    @Override
    public boolean supportsPartition() {
        return true;
    }
}
