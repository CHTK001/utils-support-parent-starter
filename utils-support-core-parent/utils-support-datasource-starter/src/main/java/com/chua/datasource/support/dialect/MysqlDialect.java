package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.dialect.StorageEngine;

import java.util.Properties;

/**
 * MySQL 8.0+ 方言实现（兼容 5.7）。
 * <p>所有 SQL 片段、引用符、关键字均可通过 {@link #properties} 覆盖：</p>
 * <ul>
 *   <li>{@code quote-open} / {@code quote-close} — 标识符引用符，默认 {@code `}</li>
 *   <li>{@code engine-keyword} — 存储引擎关键字，默认 {@code engine}</li>
 *   <li>{@code table-type} — CREATE TABLE 表类型后缀，默认 {@code ENGINE=InnoDB DEFAULT CHARSET=utf8mb4}</li>
 *   <li>{@code auto-increment-keyword} — 自增关键字，默认 {@code AUTO_INCREMENT}</li>
 *   <li>{@code alter-column-string} — 修改列关键字，默认 {@code MODIFY COLUMN}</li>
 *   <li>{@code current-timestamp-sql} — 当前时间查询，默认 {@code SELECT NOW()}</li>
 *   <li>{@code trigger-list-sql} / {@code trigger-sql} — 触发器查询模板</li>
 *   <li>{@code procedure-list-sql} / {@code procedure-sql} — 存储过程查询模板</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MysqlDialect extends AbstractDialect {

    /**
     * 构造默认 MySQL 方言。
     */
    public MysqlDialect() {
    }

    /**
     * 从属性构建 MySQL 方言。
     *
     * @param properties 配置属性，key 见类注释
     */
    public MysqlDialect(Properties properties) {
        withProperties(properties);
    }

    @Override
    /** Protocol */
    public String protocol() {
        return config("protocol", "mysql");
    }

    @Override
    /** Driver */
    public String driver() {
        return config("driver", "com.mysql.cj.jdbc.Driver");
    }

    @Override
    /** Url */
    public String url() {
        return config("url", null);
    }

    @Override
    /** 打开Quote */
    public char openQuote() {
        String v = config("quote-open", "`");
        return v != null && !v.isEmpty() ? v.charAt(0) : '`';
    }

    @Override
    /** 关闭Quote */
    public char closeQuote() {
        String v = config("quote-close", "`");
        return v != null && !v.isEmpty() ? v.charAt(0) : '`';
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
        return config("auto-increment-keyword", "AUTO_INCREMENT");
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
        return config("alter-column-string", "MODIFY COLUMN");
    }

    @Override
    /** 获取CurrentTimestamp选择String */
    public String getCurrentTimestampSelectString() {
        return config("current-timestamp-sql", "SELECT NOW()");
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
        return config("engine-keyword", "ENGINE");
    }

    @Override
    /** 获取StorageEngine */
    public StorageEngine getStorageEngine() {
        return StorageEngine.INNODB;
    }

    @Override
    /** 获取TableTypeString */
    public String getTableTypeString() {
        return config("table-type", " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @Override
    /** 原生向量支持检测（MySQL 8.0.31+ 有 VECTOR 类型） */
    public boolean supportsVector() {
        return true;
    }

    @Override
    /** JSON 支持 */
    public boolean supportsJson() {
        return true;
    }

    // ==================== 触发器 / 存储过程查询 SQL ====================

    @Override
    /** 获取TriggerListSql */
    public String getTriggerListSql(String schema) {
        String template = config("trigger-list-sql", null);
        if (template != null) {
            return appendSchemaCondition(new StringBuilder(template), "TRIGGER_SCHEMA", schema).toString();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT TRIGGER_NAME, TRIGGER_SCHEMA, EVENT_OBJECT_TABLE AS TABLE_NAME, "
                        + "ACTION_TIMING, EVENT_MANIPULATION, ACTION_STATEMENT "
                        + "FROM INFORMATION_SCHEMA.TRIGGERS");
        appendSchemaCondition(sql, "TRIGGER_SCHEMA", schema);
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
        String template = config("procedure-list-sql", null);
        if (template != null) {
            return appendSchemaCondition(new StringBuilder(template), "ROUTINE_SCHEMA", schema).toString();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT ROUTINE_SCHEMA, ROUTINE_NAME, ROUTINE_TYPE, DATA_TYPE, "
                        + "ROUTINE_DEFINITION, ROUTINE_COMMENT, SECURITY_TYPE, ROUTINE_BODY "
                        + "FROM INFORMATION_SCHEMA.ROUTINES "
                        + "WHERE ROUTINE_TYPE IN ('PROCEDURE', 'FUNCTION')");
        appendSchemaCondition(sql, "ROUTINE_SCHEMA", schema);
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

    private static void appendSchemaCondition(StringBuilder sql, String columnName, String schema) {
        if (schema != null && !schema.isEmpty()) {
            sql.append(" AND ").append(columnName).append(" = '").append(escape(schema)).append("'");
        }
    }
}
