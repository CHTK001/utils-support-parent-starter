package com.chua.jvector.support.spi;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageProvider;
import com.chua.jvector.support.configuration.JVectorStorageProperties;
import com.chua.jvector.support.storage.JVectorVectorStorage;

/**
 * JVector 向量存储 SPI 实现。
 *
 * @author CH
 */
@Spi(value = "jvector", order = 100)
public class JVectorVectorStorageProvider implements VectorStorageProvider {

    @Override
    public String name() {
        return "jvector";
    }

    @Override
    public VectorStorage create(int dimension,
                                VectorCompareAlgorithm algorithm,
                                Object properties) {
        JVectorStorageProperties props = null;
        if (properties instanceof JVectorStorageProperties p) {
            props = p;
        }
        return new JVectorVectorStorage(dimension, algorithm, props);
    }
}
