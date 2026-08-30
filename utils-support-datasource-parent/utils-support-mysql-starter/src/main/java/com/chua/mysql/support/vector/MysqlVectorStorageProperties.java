package com.chua.mysql.support.vector;

/**
 * MySQL 向量存储配置属性。
 *
 * @param tableName    向量表名称，默认 {@code vector_store}
 * @param idColumn     ID 列名，默认 {@code id}
 * @param vectorColumn 向量列名，默认 {@code vec}
 * @author CH
 * @since 4.0.0.42
 */
public record MysqlVectorStorageProperties(
        /** 向量表名称，默认 vector_store */
        String tableName,
        /** ID 列名，默认 id */
        String idColumn,
        /** 向量列名，默认 vec */
        String vectorColumn
) {
    /** 默认表名 */
    private static final String DEFAULT_TABLE = "vector_store";
    /** 默认 ID 列名 */
    private static final String DEFAULT_ID_COLUMN = "id";
    /** 默认向量列名 */
    private static final String DEFAULT_VECTOR_COLUMN = "vec";

    /**
     * 无参构造，使用默认列名和表名。
     */
    public MysqlVectorStorageProperties() {
        this(DEFAULT_TABLE, DEFAULT_ID_COLUMN, DEFAULT_VECTOR_COLUMN);
    }

    /**
     * 将对象转为配置属性，若为 null 则返回默认实例。
     *
     * @param obj 配置对象
     * @return 配置属性，为 null 时返回默认实例
     */
    public static MysqlVectorStorageProperties of(Object obj) {
        if (obj instanceof MysqlVectorStorageProperties props) {
            return props;
        }
        return new MysqlVectorStorageProperties();
    }
}
