package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;

/**
 * Apache Hive 2.x 方言实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Hive2Dialect extends AbstractDialect {

    /**
     * 支持版本
     */
    public static final String VERSION = "Apache Hive 2.x";

    @Override
    public String protocol() {
        return "hive2";
    }

    @Override
    public String driver() {
        return "org.apache.hive.jdbc.HiveDriver";
    }

    @Override
    public String url() {
        return "jdbc:hive2://<IP>:<PORT>/<DATABASE>";
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
    public boolean supportsLimit() {
        return true;
    }

    @Override
    public String processSql(String sql, Pagination pagination) {
        return sql + " LIMIT " + pagination.getLimit();
    }

    @Override
    public String getTypeName(int jdbcType, long length, int precision, int scale) {
        return switch (jdbcType) {
            case java.sql.Types.INTEGER -> "INT";
            case java.sql.Types.BIGINT -> "BIGINT";
            case java.sql.Types.SMALLINT -> "SMALLINT";
            case java.sql.Types.TINYINT -> "TINYINT";
            case java.sql.Types.VARCHAR -> "STRING";
            case java.sql.Types.CHAR -> "STRING";
            case java.sql.Types.DECIMAL -> "DECIMAL(" + precision + "," + scale + ")";
            case java.sql.Types.DOUBLE -> "DOUBLE";
            case java.sql.Types.FLOAT -> "FLOAT";
            case java.sql.Types.BOOLEAN -> "BOOLEAN";
            case java.sql.Types.TIMESTAMP -> "TIMESTAMP";
            case java.sql.Types.DATE -> "DATE";
            case java.sql.Types.CLOB -> "STRING";
            case java.sql.Types.BLOB -> "BINARY";
            default -> "STRING";
        };
    }

    @Override
    public String getCurrentTimestampSelectString() {
        return "SELECT CURRENT_TIMESTAMP";
    }

    @Override
    public boolean supportsPartition() {
        return true;
    }
}
