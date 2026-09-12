package com.chua.jvector.support.spi;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageProvider;
import com.chua.jvector.support.configuration.JVectorStorageProperties;
import com.chua.jvector.support.storage.JVectorVectorStorage;

/**
   * j向量 向量存储 SPI 实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "jvector", order = 100)
public class JVectorVectorStorageProvider implements VectorStorageProvider {

    /**
     * SPI 名称。
     *
     * @return "jvector"
     */
    @Override
    public String name() {
        return "jvector";
    }

    @Override
    /**
     * 创建
     * @param dimension 维度
     * @param algorithm algorithm
     * @param properties 属性
     */
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
