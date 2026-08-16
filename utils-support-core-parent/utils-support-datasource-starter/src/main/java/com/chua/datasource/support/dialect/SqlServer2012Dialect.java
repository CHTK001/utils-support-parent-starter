package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;

/**
 * SQL Server 2012 方言实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SqlServer2012Dialect extends AbstractDialect {

    /**
     * 支持版本
     */
    public static final String VERSION = "SQL Server 2012";

    @Override
    public String protocol() {
        return "sqlserver2012";
    }

    @Override
    public String driver() {
        return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    }

    @Override
    public String url() {
        return "jdbc:sqlserver://<IP>:<PORT>;databaseName=<DATABASE>;encrypt=false";
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
        return sql + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    @Override
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
            case java.sql.Types.TIMESTAMP -> "DATETIME2";
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
    public String getAutoIncrementKeyword() {
        return "IDENTITY(1,1)";
    }

    @Override
    public String getAlterColumnString() {
        return "ALTER COLUMN";
    }

    @Override
    public String getCurrentTimestampSelectString() {
        return "SELECT GETDATE()";
    }

    @Override
    public String getDropIndexString(String indexName, String tableName) {
        return "DROP INDEX " + indexName + " ON " + quote(tableName);
    }

    @Override
    public String getRenameIndexString(String oldIndexName, String newIndexName, String tableName) {
        return "EXEC sp_rename N'" + quote(tableName) + "." + oldIndexName + "', N'" + newIndexName + "', N'INDEX'";
    }
}
