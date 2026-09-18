package com.chua.postgresql.support.vector;

/**
* PostgreSQL pgvector 向量存储配置属性。
*
* @param tableName    向量表名称，默认 {@code vector_store}
* @param idColumn     标识 列名，默认 {@code id}
* @param vectorColumn 向量列名，默认 {@code embedding}
* @param ivfflatLists ivfflat 索引的 列表 数，默认 100（仅 ivfflat 模式有效）
* @param hnswM        HNSW 索引的 M 参数，默认 16（仅 hnsw 模式有效）
* @param hnswEfSearch HNSW 搜索时的 ef_搜索，默认 40
* @author CH
* @since 4.0.0.42
 */
public record PostgresqlVectorStorageProperties(
        /** 向量表名称，默认 向量_存储 */
        String tableName,
        /** 标识 列名，默认 标识 */
        String idColumn,
        /** 向量列名，默认 嵌入 */
        String vectorColumn,
        /** ivfflat 列表 数，默认 100 */
        int ivfflatLists,
        /** HNSW M 参数，默认 16 */
        int hnswM,
        /**
        * HNSW ef_搜索，默认 40
        *
        * @param obj obj
        * @return 的的结果
        */
        int hnswEfSearch
) {
    private static final String DEFAULT_TABLE = "vector_store";
    private static final String DEFAULT_ID_COLUMN = "id";
    /**
    * postgresql向量storage属性。
    * @return postgresql向量storage属性的结果
    * @param obj obj
    */
    private static final String DEFAULT_VECTOR_COLUMN = "embedding";
    private static final int DEFAULT_IVFFLAT_LISTS = 100;
    private static final int DEFAULT_HNSW_M = 16;
    private static final int DEFAULT_HNSW_EF_SEARCH = 40;
/**
* postgresql向量storage属性。
* @return postgresql向量storage属性的结果
 */

    public PostgresqlVectorStorageProperties() {
        this(DEFAULT_TABLE, DEFAULT_ID_COLUMN, DEFAULT_VECTOR_COLUMN,
                DEFAULT_IVFFLAT_LISTS, DEFAULT_HNSW_M, DEFAULT_HNSW_EF_SEARCH);
    }

    public static PostgresqlVectorStorageProperties of(Object obj) {
        if (obj instanceof PostgresqlVectorStorageProperties props) {
            return props;
        }
        return new PostgresqlVectorStorageProperties();
    }
}
