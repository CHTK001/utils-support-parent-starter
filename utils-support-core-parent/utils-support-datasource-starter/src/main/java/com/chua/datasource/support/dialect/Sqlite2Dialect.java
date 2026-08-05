package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;
/**
 * @author CH
 */

public class Sqlite2Dialect extends AbstractDialect {

    public static final String VERSION = "SQLite 2.x";

    @Override
    public String protocol() {
        return "sqlite2";
    }

    @Override
    public String driver() {
        return "org.sqlite.JDBC";
    }

    @Override
    public String url() {
        return "jdbc:sqlite:<DATABASE>";
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
    public boolean supportsLimit() {
        return false;
    }

    @Override
    public String processSql(String sql, Pagination pagination) {
        return sql;
    }

    @Override
    public String getTypeName(int jdbcType, long length, int precision, int scale) {
        return switch (jdbcType) {
            case java.sql.Types.INTEGER,
                 java.sql.Types.BIGINT,
                 java.sql.Types.SMALLINT,
                 java.sql.Types.TINYINT,
                 java.sql.Types.BOOLEAN -> "INTEGER";
            case java.sql.Types.VARCHAR,
                 java.sql.Types.CHAR,
                 java.sql.Types.CLOB,
                 java.sql.Types.DATE,
                 java.sql.Types.TIMESTAMP -> "TEXT";
            case java.sql.Types.DECIMAL,
                 java.sql.Types.FLOAT,
                 java.sql.Types.DOUBLE -> "REAL";
            case java.sql.Types.BLOB -> "BLOB";
            default -> "TEXT";
        };
    }

    @Override
    public String getAutoIncrementKeyword() {
        return "AUTOINCREMENT";
    }

    @Override
    public boolean supportsUpsert() {
        return false;
    }

    // ==================== 触发器查询 SQL（SQLite 不支持存储过程） ====================

    @Override
    public String getTriggerListSql(String schema) {
        return "SELECT name AS trigger_name, tbl_name AS table_name, sql AS action_statement "
                + "FROM sqlite_master WHERE type = 'trigger'";
    }

    @Override
    public String getTriggerSql(String triggerName, String schema) {
        if (triggerName == null || triggerName.isEmpty()) {
            return null;
        }
        return "SELECT name AS trigger_name, tbl_name AS table_name, sql AS action_statement "
                + "FROM sqlite_master WHERE type = 'trigger' AND name = '" + escape(triggerName) + "'";
    }
}
