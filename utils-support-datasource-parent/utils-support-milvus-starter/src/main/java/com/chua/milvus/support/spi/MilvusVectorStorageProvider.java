package com.chua.milvus.support.spi;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageProvider;
import com.chua.milvus.support.configuration.MilvusStorageProperties;
import com.chua.milvus.support.storage.MilvusVectorStorage;

/**
 * Milvus 向量存储 SPI 实现。
 *
 * @author CH
 */
@Spi(value = "milvus", order = 100)
public class MilvusVectorStorageProvider implements VectorStorageProvider {

    @Override
    public String name() {
        return "milvus";
    }

    @Override
    public VectorStorage create(int dimension,
                                VectorCompareAlgorithm algorithm,
                                Object properties) {
        if (!(properties instanceof MilvusStorageProperties props)) {
            throw new IllegalArgumentException(
                    "Milvus SPI 需要 MilvusStorageProperties，当前类型: "
                            + (properties == null ? "null" : properties.getClass().getName()));
        }
        return new MilvusVectorStorage(dimension, algorithm,
                props.getHost(), props.getPort(),
                props.getCollection(), props.getToken());
    }
}