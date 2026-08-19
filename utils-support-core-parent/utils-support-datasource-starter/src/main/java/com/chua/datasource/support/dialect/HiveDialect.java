package com.chua.datasource.support.dialect;

import com.chua.common.support.lang.datasource.dialect.Pagination;

/**
 * Apache Hive 3.x 方言实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HiveDialect extends AbstractDialect {

    /**
     * 支持版本
     */
    public static final String VERSION = "Apache Hive 3.x";

    @Override
    /** Protocol */
    public String protocol() {
        return "hive";
    }

    @Override
    /** Driver */
    public String driver() {
        return "org.apache.hive.jdbc.HiveDriver";
    }

    @Override
    /** Url */
    public String url() {
        return "jdbc:hive2://<IP>:<PORT>/<DATABASE>";
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
    /** SupportsLimit */
    public boolean supportsLimit() {
        return true;
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
            case java.sql.Types.VARCHAR -> "STRING";
            case java.sql.Types.CHAR -> "STRING";
            case java.sql.Types.DECIMAL -> "DECIMAL(" + precision + "," + scale + ")";
            case java.sql.Types.DOUBLE -> "DOUBLE";
            case java.sql.Types.FLOAT -> "FLOAT";
            case java.sql.Types.BOOLEAN -> "BOOLEAN";
            case java.sql.Types.TIMESTAMP -> "TIMESTAMP";
            case java.sql.Types.DATE -> "DATE";
            case java.sql.Types.CLOB -> "STRING";
            case java.sql.Types.BLOB -> "BINARY";
            default -> "STRING";
        };
    }

    @Override
    /** 获取CurrentTimestamp选择String */
    public String getCurrentTimestampSelectString() {
        return "SELECT CURRENT_TIMESTAMP";
    }

    @Override
    /** SupportsPartition */
    public boolean supportsPartition() {
        return true;
    }
}
