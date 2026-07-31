package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;
/**
 * @author CH
 */

public class SqliteDialect extends AbstractDialect {

    public static final String VERSION = "SQLite 3.x";

    @Override
    public String protocol() {
        return "sqlite";
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
    public String processSql(String sql, Pagination pagination) {
        boolean hasOffset = pagination.getOffset() > 0;
        return sql + " LIMIT " + pagination.getLimit()
                + (hasOffset ? " OFFSET " + pagination.getOffset() : "");
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
        return true;
    }

    @Override
    public String getUpsertSql(String tableName, String columns, String values, String updateSet) {
        return "insert into " + quote(tableName) + " (" + columns + ") values (" + values
                + ") on conflict do update set " + updateSet;
    }
}
