package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.dialect.meta.IndexMetadata;

/**
 * DuckDB 0.8+ 方言实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DuckdbDialect extends AbstractDialect {

    /**
     * 支持版本
     */
    public static final String VERSION = "DuckDB 0.8+";

    @Override
    /** Protocol */
    public String protocol() {
        return "duckdb";
    }

    @Override
    /** Driver */
    public String driver() {
        return "org.duckdb.DuckDBDriver";
    }

    @Override
    /** Url */
    public String url() {
        return "jdbc:duckdb:<DATABASE>";
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
            case java.sql.Types.INTEGER -> "INTEGER";
            case java.sql.Types.BIGINT -> "BIGINT";
            case java.sql.Types.SMALLINT -> "SMALLINT";
            case java.sql.Types.TINYINT -> "TINYINT";
            case java.sql.Types.VARCHAR -> "VARCHAR";
            case java.sql.Types.CHAR -> "CHAR";
            case java.sql.Types.DECIMAL -> "DECIMAL(" + precision + "," + scale + ")";
            case java.sql.Types.DOUBLE -> "DOUBLE";
            case java.sql.Types.FLOAT -> "FLOAT";
            case java.sql.Types.BOOLEAN -> "BOOLEAN";
            case java.sql.Types.TIMESTAMP -> "TIMESTAMP";
            case java.sql.Types.DATE -> "DATE";
            case java.sql.Types.TIME -> "TIME";
            case java.sql.Types.CLOB -> "CLOB";
            case java.sql.Types.BLOB -> "BLOB";
            case java.sql.Types.LONGVARCHAR -> "VARCHAR";
            case java.sql.Types.LONGNVARCHAR -> "VARCHAR";
            default -> "VARCHAR";
        };
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

    @Override
    /** 获取创建IndexString */
    public String getCreateIndexString(IndexMetadata indexMetadata) {
        StringBuilder sql = new StringBuilder();
        if (indexMetadata.isUnique()) {
            sql.append("CREATE UNIQUE INDEX ");
        } else {
            sql.append("CREATE INDEX ");
        }
        sql.append(indexMetadata.getName()).append(" ON ").append(indexMetadata.getTableName()).append(" (");
        if (indexMetadata.getColumns() != null && !indexMetadata.getColumns().isEmpty()) {
            sql.append(String.join(", ", indexMetadata.getColumns()));
        } else if (indexMetadata.getColumnName() != null && !indexMetadata.getColumnName().isEmpty()) {
            sql.append(indexMetadata.getColumnName());
        }
        sql.append(")");
        return sql.toString();
    }

    @Override
    /** 获取DropIndexString */
    public String getDropIndexString(String indexName, String tableName) {
        return "DROP INDEX IF EXISTS " + indexName;
    }

    @Override
    /** 获取重命名IndexString */
    public String getRenameIndexString(String oldIndexName, String newIndexName, String tableName) {
        return "ALTER INDEX " + oldIndexName + " RENAME TO " + newIndexName;
    }
}
