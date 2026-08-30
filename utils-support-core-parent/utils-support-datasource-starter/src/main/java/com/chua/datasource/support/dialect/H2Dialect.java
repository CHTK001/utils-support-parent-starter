package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;

import java.util.Properties;

/**
 * H2 2.x 方言实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class H2Dialect extends AbstractDialect {

    public static final String VERSION = "H2 2.x";

    public H2Dialect() {
    }

    public H2Dialect(Properties properties) {
        withProperties(properties);
    }

    @Override
    public String protocol() {
        return properties != null
                ? properties.getProperty("protocol", "h2")
                : "h2";
    }

    @Override
    public String driver() {
        String prop = properties != null ? properties.getProperty("driver") : null;
        return prop != null ? prop : "org.h2.Driver";
    }

    @Override
    public String url() {
        return properties != null ? properties.getProperty("url") : null;
    }

    @Override
    /** 打开Quote */
    public char openQuote() {
        return '"';
    }

    @Override
    /** 关闭Quote */
    public char closeQuote() {
        return '"';
    }

    @Override
    /** 处理Sql */
    public String processSql(String sql, Pagination pagination) {
        return sql + " LIMIT " + pagination.getLimit() + " OFFSET " + pagination.getOffset();
    }

    @Override
    /** 获取TypeName */
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
    /** 获取AutoIncrementKeyword */
    public String getAutoIncrementKeyword() {
        return "AUTO_INCREMENT";
    }

    @Override
    /** SupportsUpsert */
    public boolean supportsUpsert() {
        return true;
    }

    @Override
    /** 获取UpsertSql */
    public String getUpsertSql(String tableName, String columns, String values, String updateSet) {
        return "MERGE INTO " + quote(tableName) + " (" + columns + ") VALUES (" + values + ")";
    }

    @Override
    /** 获取AlterColumnString */
    public String getAlterColumnString() {
        return "ALTER COLUMN";
    }

    @Override
    /** 获取CurrentTimestamp选择String */
    public String getCurrentTimestampSelectString() {
        return "SELECT CURRENT_TIMESTAMP";
    }

    // ==================== 触发器查询 SQL（H2 不支持存储过程） ====================

    @Override
    /** 获取TriggerListSql */
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
    /** 获取TriggerSql */
    public String getTriggerSql(String triggerName, String schema) {
        if (triggerName == null || triggerName.isEmpty()) {
            return null;
        }
        return "SELECT * FROM (" + getTriggerListSql(schema) + ") T "
                + "WHERE TRIGGER_NAME = '" + escape(triggerName) + "'";
    }
}
