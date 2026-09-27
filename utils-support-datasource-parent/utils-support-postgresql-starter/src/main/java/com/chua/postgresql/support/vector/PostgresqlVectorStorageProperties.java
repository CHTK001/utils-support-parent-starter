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
        /**
         * 向量表名称，默认 向量_存储
        */
        String tableName,
        /**
         * 标识 列名，默认 标识
        */
        String idColumn,
        /**
         * 向量列名，默认 嵌入
        */
        String vectorColumn,
        /**
         * ivfflat 列表 数，默认 100
        */
        int ivfflatLists,
        /**
         * HNSW M 参数，默认 16
        */
        int hnswM,
        /**
         * HNSW ef_搜索，默认 40
         */
        int hnswEfSearch
) {
    private static final String DEFAULT_TABLE = "vector_store";
    private static final String DEFAULT_ID_COLUMN = "id";
    private static final String DEFAULT_VECTOR_COLUMN = "embedding";
    private static final int DEFAULT_IVFFLAT_LISTS = 100;
    private static final int DEFAULT_HNSW_M = 16;
    private static final int DEFAULT_HNSW_EF_SEARCH = 40;

    /**
     * 规范构造器：三个标识列名不可为空。
     *
     * <p>value class 前置条件——引用组件空值敌对。三个组件都会被
     * {@code PostgresqlVectorStorage} 当作 SQL 标识符校验，空值本就不可用，
     * 故在此处快速失败。全部构造点（无参构造 / {@link #of(Object)} /
     * 业务侧显式列名）传入的均为非 null 值，因此不改变既有行为。
     * 三个 {@code int} 组件为基本类型，无需空值校验。</p>
     */
    public PostgresqlVectorStorageProperties {
        tableName = java.util.Objects.requireNonNull(tableName, "tableName 不能为 null");
        idColumn = java.util.Objects.requireNonNull(idColumn, "idColumn 不能为 null");
        vectorColumn = java.util.Objects.requireNonNull(vectorColumn, "vectorColumn 不能为 null");
    }

    /**
     * 使用默认配置创建向量存储属性。
     *
     * <p>刻意保留的无参构造器：它只是把六个默认常量转发给规范构造器，
     * 不引入第二种状态。与 {@code MysqlVectorStorageProperties} 保持一致，
     * 供 {@link PostgresqlVectorStorageProvider} 与
     * {@link PostgresqlVectorStorage} 在未配置时零参数构造。</p>
     */
    public PostgresqlVectorStorageProperties() {
        this(DEFAULT_TABLE, DEFAULT_ID_COLUMN, DEFAULT_VECTOR_COLUMN,
                DEFAULT_IVFFLAT_LISTS, DEFAULT_HNSW_M, DEFAULT_HNSW_EF_SEARCH);
    }

    /**
     * of。
     *
     * @param obj 对象，不允许为 null
     * @return PostgresqlVectorStorage属性 对象
     */
    public static PostgresqlVectorStorageProperties of(Object obj) {
        if (obj instanceof PostgresqlVectorStorageProperties props) {
            return props;
        }
        return new PostgresqlVectorStorageProperties();
    }
}
