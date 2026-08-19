package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;

/**
 * SQL Server 2008 方言实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SqlServer2008Dialect extends AbstractDialect {

    /**
     * 支持版本
     */
    public static final String VERSION = "SQL Server 2008";

    @Override
    /** Protocol */
    public String protocol() {
        return "sqlserver2008";
    }

    @Override
    /** Driver */
    public String driver() {
        return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    }

    @Override
    /** Url */
    public String url() {
        return "jdbc:sqlserver://<IP>:<PORT>;databaseName=<DATABASE>";
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
        int offset = pagination.getOffset();
        int limit = pagination.getLimit();
        int endRow = offset + limit;
        return "SELECT * FROM (SELECT TMP.*, ROW_NUMBER() OVER (ORDER BY (SELECT 0)) AS ROW_ID FROM ("
                + sql + ") TMP) TMP2 WHERE ROW_ID > " + offset + " AND ROW_ID <= " + endRow;
    }

    @Override
    /** 获取TypeName */
    public String getTypeName(int jdbcType, long length, int precision, int scale) {
        return switch (jdbcType) {
            case java.sql.Types.INTEGER -> "INT";
            case java.sql.Types.BIGINT -> "BIGINT";
            case java.sql.Types.SMALLINT -> "SMALLINT";
            case java.sql.Types.TINYINT -> "TINYINT";
            case java.sql.Types.VARCHAR -> length > 0 ? "NVARCHAR(" + length + ")" : "NVARCHAR(255)";
            case java.sql.Types.CHAR -> "NCHAR(" + length + ")";
            case java.sql.Types.DECIMAL -> "DECIMAL(" + precision + "," + scale + ")";
            case java.sql.Types.DOUBLE -> "FLOAT";
            case java.sql.Types.FLOAT -> "REAL";
            case java.sql.Types.BOOLEAN -> "BIT";
            case java.sql.Types.TIMESTAMP -> "DATETIME";
            case java.sql.Types.DATE -> "DATE";
            case java.sql.Types.TIME -> "TIME";
            case java.sql.Types.CLOB -> "NVARCHAR(MAX)";
            case java.sql.Types.BLOB -> "VARBINARY(MAX)";
            case java.sql.Types.LONGVARCHAR -> "NVARCHAR(MAX)";
            case java.sql.Types.LONGNVARCHAR -> "NVARCHAR(MAX)";
            default -> "NVARCHAR(255)";
        };
    }

    @Override
    /** 获取AutoIncrementKeyword */
    public String getAutoIncrementKeyword() {
        return "IDENTITY(1,1)";
    }

    @Override
    /** 获取AlterColumnString */
    public String getAlterColumnString() {
        return "ALTER COLUMN";
    }

    @Override
    /** 获取CurrentTimestamp选择String */
    public String getCurrentTimestampSelectString() {
        return "SELECT GETDATE()";
    }

    @Override
    /** 获取DropIndexString */
    public String getDropIndexString(String indexName, String tableName) {
        return "DROP INDEX " + indexName + " ON " + quote(tableName);
    }

    @Override
    /** 获取重命名IndexString */
    public String getRenameIndexString(String oldIndexName, String newIndexName, String tableName) {
        return "EXEC sp_rename N'" + quote(tableName) + "." + oldIndexName + "', N'" + newIndexName + "', N'INDEX'";
    }

    // ==================== 触发器 / 存储过程查询 SQL ====================

    @Override
    /** 获取TriggerListSql */
    public String getTriggerListSql(String schema) {
        StringBuilder sql = new StringBuilder(
                "SELECT tr.name AS trigger_name, sc.name AS trigger_schema, tb.name AS table_name, "
                        + "OBJECT_DEFINITION(tr.object_id) AS action_statement, "
                        + "CASE WHEN tr.is_disabled = 0 THEN 'ENABLED' ELSE 'DISABLED' END AS status "
                        + "FROM sys.triggers tr "
                        + "JOIN sys.tables tb ON tb.object_id = tr.parent_id "
                        + "JOIN sys.schemas sc ON sc.schema_id = tb.schema_id");
        if (schema != null && !schema.isEmpty()) {
            sql.append(" WHERE sc.name = '").append(escape(schema)).append("'");
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
                + "WHERE trigger_name = '" + escape(triggerName) + "'";
    }

    @Override
    /** 获取ProcedureListSql */
    public String getProcedureListSql(String schema) {
        StringBuilder sql = new StringBuilder(
                "SELECT sc.name AS routine_schema, o.name AS routine_name, "
                        + "CASE o.type WHEN 'P' THEN 'PROCEDURE' ELSE 'FUNCTION' END AS routine_type, "
                        + "OBJECT_DEFINITION(o.object_id) AS routine_definition "
                        + "FROM sys.objects o JOIN sys.schemas sc ON sc.schema_id = o.schema_id "
                        + "WHERE o.type IN ('P', 'FN', 'TF', 'IF')");
        if (schema != null && !schema.isEmpty()) {
            sql.append(" AND sc.name = '").append(escape(schema)).append("'");
        }
        return sql.toString();
    }

    @Override
    /** 获取ProcedureSql */
    public String getProcedureSql(String procedureName, String schema) {
        if (procedureName == null || procedureName.isEmpty()) {
            return null;
        }
        return "SELECT * FROM (" + getProcedureListSql(schema) + ") T "
                + "WHERE routine_name = '" + escape(procedureName) + "'";
    }
}
