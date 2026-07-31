package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;
/**
 * @author CH
 */

public class H2V1Dialect extends AbstractDialect {

    public static final String VERSION = "H2 1.x";

    @Override
    public String protocol() {
        return "h2v1";
    }

    @Override
    public String driver() {
        return "org.h2.Driver";
    }

    @Override
    public String url() {
        return "jdbc:h2:<DATABASE>";
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
        int offset = pagination.getOffset();
        int limit = pagination.getLimit();
        if (offset > 0) {
            return sql + " LIMIT " + limit + " OFFSET " + offset;
        }
        return sql + " LIMIT " + limit;
    }

    @Override
    public String getTypeName(int jdbcType, long length, int precision, int scale) {
        return switch (jdbcType) {
            case java.sql.Types.INTEGER -> "INT";
            case java.sql.Types.BIGINT -> "BIGINT";
            case java.sql.Types.SMALLINT -> "SMALLINT";
            case java.sql.Types.TINYINT -> "TINYINT";
            case java.sql.Types.VARCHAR -> length > 0 ? "VARCHAR(" + length + ")" : "VARCHAR(255)";
            case java.sql.Types.CHAR -> "CHAR(" + length + ")";
            case java.sql.Types.DECIMAL -> "DECIMAL(" + precision + "," + scale + ")";
            case java.sql.Types.DOUBLE -> "DOUBLE";
            case java.sql.Types.FLOAT -> "FLOAT";
            case java.sql.Types.BOOLEAN -> "BOOLEAN";
            case java.sql.Types.TIMESTAMP -> "TIMESTAMP";
            case java.sql.Types.DATE -> "DATE";
            case java.sql.Types.TIME -> "TIME";
            case java.sql.Types.CLOB -> "CLOB";
            case java.sql.Types.BLOB -> "BLOB";
            case java.sql.Types.LONGVARCHAR -> "VARCHAR(255)";
            case java.sql.Types.LONGNVARCHAR -> "VARCHAR(255)";
            default -> "VARCHAR(255)";
        };
    }

    @Override
    public String getAutoIncrementKeyword() {
        return "AUTO_INCREMENT";
    }

    @Override
    public boolean supportsUpsert() {
        return false;
    }

    @Override
    public String getAlterColumnString() {
        return "ALTER COLUMN";
    }

    @Override
    public String getCurrentTimestampSelectString() {
        return "SELECT CURRENT_TIMESTAMP()";
    }
}
