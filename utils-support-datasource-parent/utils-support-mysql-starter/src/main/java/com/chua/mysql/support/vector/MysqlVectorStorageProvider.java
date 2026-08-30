package com.chua.mysql.support.vector;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageProvider;

import javax.sql.DataSource;

/**
 * MySQL 向量存储 SPI 实现，基于 MySQL 8.0.31+ 原生 VECTOR 类型。
 * <p>
 * 通过 {@code properties} 参数传入 {@link DataSource}：
 * <pre>{@code
 * // 链式方式（推荐）
 * VectorStorage storage = VectorStorageProvider.of("mysql")
 *         .dimension(128)
 *         .algorithm("cosine")
 *         .properties(new MysqlVectorStorageProps(dataSource))
 *         .build();
 *
 * // 指定自定义表名
 * var props = new MysqlVectorStorageProperties("my_vectors", "vid", "embedding");
 * VectorStorage storage = VectorStorageProvider.of("mysql")
 *         .dimension(768)
 *         .algorithm("cosine")
 *         .properties(new MysqlVectorStorageProps(dataSource, props))
 *         .build();
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "mysql", order = 50)
public class MysqlVectorStorageProvider implements VectorStorageProvider {

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public VectorStorage create(int dimension, VectorCompareAlgorithm algorithm, Object properties) {
        if (!(properties instanceof MysqlVectorStorageProps wrapped)) {
            throw new IllegalArgumentException(
                    "MySQL 向量存储需要 MysqlVectorStorageProps 类型的 properties，"
                    + "包含 DataSource 和 MysqlVectorStorageProperties");
        }
        return new MysqlVectorStorage(wrapped.dataSource(), dimension, algorithm, wrapped.properties());
    }

    /**
     * 包装属性，持有 DataSource 和向量存储配置。
     *
     * @param dataSource  JDBC 数据源
     * @param properties  向量存储配置
     */
    public record MysqlVectorStorageProps(DataSource dataSource, MysqlVectorStorageProperties properties) {
        /**
         * 便捷构造。
         */
        public MysqlVectorStorageProps(DataSource dataSource) {
            this(dataSource, new MysqlVectorStorageProperties());
        }
    }
}
