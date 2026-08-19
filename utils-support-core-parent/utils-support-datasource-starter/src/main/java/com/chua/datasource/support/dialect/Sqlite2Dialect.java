package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;

/**
 * SQLite 2.x 方言实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Sqlite2Dialect extends AbstractDialect {

    /**
     * 支持版本
     */
    public static final String VERSION = "SQLite 2.x";

    @Override
    /** Protocol */
    public String protocol() {
        return "sqlite2";
    }

    @Override
    /** Driver */
    public String driver() {
        return "org.sqlite.JDBC";
    }

    @Override
    /** Url */
    public String url() {
        return "jdbc:sqlite:<DATABASE>";
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
    /** SupportsLimit */
    public boolean supportsLimit() {
        return false;
    }

    @Override
    /** 处理Sql */
    public String processSql(String sql, Pagination pagination) {
        return sql;
    }

    @Override
    /** 获取TypeName */
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
    /** 获取AutoIncrementKeyword */
    public String getAutoIncrementKeyword() {
        return "AUTOINCREMENT";
    }

    @Override
    /** SupportsUpsert */
    public boolean supportsUpsert() {
        return false;
    }

    // ==================== 触发器查询 SQL（SQLite 不支持存储过程） ====================

    @Override
    /** 获取TriggerListSql */
    public String getTriggerListSql(String schema) {
        return "SELECT name AS trigger_name, tbl_name AS table_name, sql AS action_statement "
                + "FROM sqlite_master WHERE type = 'trigger'";
    }

    @Override
    /** 获取TriggerSql */
    public String getTriggerSql(String triggerName, String schema) {
        if (triggerName == null || triggerName.isEmpty()) {
            return null;
        }
        return "SELECT name AS trigger_name, tbl_name AS table_name, sql AS action_statement "
                + "FROM sqlite_master WHERE type = 'trigger' AND name = '" + escape(triggerName) + "'";
    }
}
