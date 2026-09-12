package com.chua.postgresql.support.vector;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageProvider;

import javax.sql.DataSource;

/**
 * PostgreSQL pgvector 向量存储 SPI 实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "postgresql", order = 60)
public class PostgresqlVectorStorageProvider implements VectorStorageProvider {

    @Override
    public String name() {
        return "postgresql";
    }

    @Override
    public VectorStorage create(int dimension, VectorCompareAlgorithm algorithm, Object properties) {
        if (!(properties instanceof PgVectorStorageProps wrapped)) {
            throw new IllegalArgumentException(
                    "PostgreSQL 向量存储需要 PgVectorStorageProps 类型，包含 DataSource");
        }
        return new PostgresqlVectorStorage(
                wrapped.dataSource(), dimension, algorithm, wrapped.properties());
    }

    /**
      * 包装属性，持有 数据源 和向量存储配置。
     * @param dataSource 数据源
     * @return pg向量storageprops的结果
     */
    public record PgVectorStorageProps(
            DataSource dataSource,
            PostgresqlVectorStorageProperties properties
    ) {
        public PgVectorStorageProps(DataSource dataSource) {
            this(dataSource, new PostgresqlVectorStorageProperties());
        }
    }
}
