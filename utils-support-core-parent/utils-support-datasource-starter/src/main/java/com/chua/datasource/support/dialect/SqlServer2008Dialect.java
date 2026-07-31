package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;
/**
 * @author CH
 */

public class SqlServer2008Dialect extends AbstractDialect {

    public static final String VERSION = "SQL Server 2008";

    @Override
    public String protocol() {
        return "sqlserver2008";
    }

    @Override
    public String driver() {
        return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    }

    @Override
    public String url() {
        return "jdbc:sqlserver://<IP>:<PORT>;databaseName=<DATABASE>";
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
        int endRow = offset + limit;
        return "SELECT * FROM (SELECT TMP.*, ROW_NUMBER() OVER (ORDER BY (SELECT 0)) AS ROW_ID FROM ("
                + sql + ") TMP) TMP2 WHERE ROW_ID > " + offset + " AND ROW_ID <= " + endRow;
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
