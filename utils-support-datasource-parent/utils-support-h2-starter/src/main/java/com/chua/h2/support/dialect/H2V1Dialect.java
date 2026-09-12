package com.chua.h2.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;

/**
* H2 1.x 方言实现。
*
* @author CH
* @since 4.0.0.42
 */
public class H2V1Dialect extends com.chua.datasource.support.dialect.AbstractDialect {

    public static final String VERSION = "H2 1.x"; // 版本

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

    @Override
    public String getTriggerListSql(String schema) {
        StringBuilder sql = new StringBuilder(
                "SELECT TRIGGER_NAME, TRIGGER_SCHEMA, EVENT_OBJECT_TABLE AS TABLE_NAME, "
                        + "ACTION_TIMING, EVENT_MANIPULATION, ACTION_STATEMENT "
                        + "FROM INFORMATION_SCHEMA.TRIGGERS");
        if (schema != null && !schema.isEmpty()) {
            sql.append(" WHERE TRIGGER_SCHEMA = '").append(escape(schema)).append("'");
        }
        return sql.toString();
    }

    @Override
    public String getTriggerSql(String triggerName, String schema) {
        if (triggerName == null || triggerName.isEmpty()) {
            return null;
        }
        return "SELECT * FROM (" + getTriggerListSql(schema) + ") T "
                + "WHERE TRIGGER_NAME = '" + escape(triggerName) + "'";
    }
}
