package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;

/**
 * MariaDB 10.6+ 方言实现（兼容 10.3/10.5）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MariaDbDialect extends AbstractDialect {

    /**
     * 支持版本
     */
    public static final String VERSION = "MariaDB 10.6+ (兼容 10.3/10.5)";

    @Override
    /** Protocol */
    public String protocol() {
        return "mariadb";
    }

    @Override
    /** Driver */
    public String driver() {
        return "org.mariadb.jdbc.Driver";
    }

    @Override
    /** Url */
    public String url() {
        return "jdbc:mariadb://<IP>:<PORT>/<DATABASE>?useSSL=false&serverTimezone=Asia/Shanghai";
    }

    @Override
    /** 打开Quote */
    public char openQuote() {
        return '`';
    }

    @Override
    /** 关闭Quote */
    public char closeQuote() {
        return '`';
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
            case java.sql.Types.BOOLEAN -> "TINYINT(1)";
            case java.sql.Types.TIMESTAMP -> "DATETIME";
            case java.sql.Types.DATE -> "DATE";
            case java.sql.Types.TIME -> "TIME";
            case java.sql.Types.CLOB -> "TEXT";
            case java.sql.Types.BLOB -> "BLOB";
            case java.sql.Types.LONGVARCHAR -> "TEXT";
            case java.sql.Types.LONGNVARCHAR -> "TEXT";
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
        return "insert into " + quote(tableName) + " (" + columns + ") values (" + values
                + ") on duplicate key update " + updateSet;
    }

    @Override
    /** 获取AlterColumnString */
    public String getAlterColumnString() {
        return "MODIFY COLUMN";
    }

    @Override
    /** 获取CurrentTimestamp选择String */
    public String getCurrentTimestampSelectString() {
        return "SELECT NOW()";
    }

    @Override
    /** SupportsInlineComment */
    public boolean supportsInlineComment() {
        return true;
    }

    @Override
    /** SupportsPartition */
    public boolean supportsPartition() {
        return true;
    }

    @Override
    /** 获取EngineKeyword */
    public String getEngineKeyword() {
        return "ENGINE";
    }

    @Override
    /** 获取TableTypeString */
    public String getTableTypeString() {
        return " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    // ==================== 触发器 / 存储过程查询 SQL（同 MySQL 信息 schema） ====================

    @Override
    /** 获取TriggerListSql */
    public String getTriggerListSql(String schema) {
        StringBuilder sql = new StringBuilder(
                "SELECT TRIGGER_NAME, TRIGGER_SCHEMA, EVENT_OBJECT_TABLE AS TABLE_NAME, "
                        + "ACTION_TIMING, EVENT_MANIPULATION, ACTION_STATEMENT "
                        + "FROM INFORMATION_SCHEMA.TRIGGERS");
        appendSchema(sql, "TRIGGER_SCHEMA", schema);
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

    @Override
    /** 获取ProcedureListSql */
    public String getProcedureListSql(String schema) {
        StringBuilder sql = new StringBuilder(
                "SELECT ROUTINE_SCHEMA, ROUTINE_NAME, ROUTINE_TYPE, DATA_TYPE, "
                        + "ROUTINE_DEFINITION, ROUTINE_COMMENT, SECURITY_TYPE, ROUTINE_BODY "
                        + "FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_TYPE IN ('PROCEDURE', 'FUNCTION')");
        appendSchema(sql, "ROUTINE_SCHEMA", schema);
        return sql.toString();
    }

    @Override
    /** 获取ProcedureSql */
    public String getProcedureSql(String procedureName, String schema) {
        if (procedureName == null || procedureName.isEmpty()) {
            return null;
        }
        return "SELECT * FROM (" + getProcedureListSql(schema) + ") T "
                + "WHERE ROUTINE_NAME = '" + escape(procedureName) + "'";
    }

    /**
     * 为查询 SQL 追加 schema 过滤条件。
     *
     * @param sql    SQL 构建器
     * @param column schema 列名
     * @param schema schema 名称，null 或空时跳过
     */
    private static void appendSchema(StringBuilder sql, String column, String schema) {
        if (schema != null && !schema.isEmpty()) {
            sql.append(" AND ").append(column).append(" = '").append(escape(schema)).append("'");
        }
    }
}
