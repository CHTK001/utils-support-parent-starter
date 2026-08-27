package com.chua.vector.support.spi;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageProvider;
import com.chua.vector.support.configuration.VectorStorageProperties;
import com.chua.vector.support.storage.CuvsVectorStorage;
import com.chua.vector.support.storage.JvectorVectorStorageDelegate;
import lombok.extern.slf4j.Slf4j;

/**
 * 向量存储 SPI 工厂，自动根据运行环境选择 cuVS GPU 或 jvector CPU 后端。
 *
 * <p>检测逻辑：
 * <ul>
 *   <li>显式指定 backend=CUVS：尝试创建 cuVS 资源，失败则降级到 jvector</li>
 *   <li>显式指定 backend=JVECTOR：直接使用 jvector</li>
 *   <li>backend=AUTO（默认）：尝试 cuVS，失败自动降级到 jvector</li>
 * </ul>
 * </p>
 *
 * <pre>{@code
 * // 使用方式一：链式构建（自动检测）
 * VectorStorage storage = VectorStorageProvider.of("vector")
 *         .dimension(768).algorithm("cosine")
 *         .properties(new VectorStorageProperties()
 *                 .setIndexType(VectorStorageProperties.CuvsIndexType.CAGRA))
 *         .build();
 *
 * // 使用方式二：强制指定 CPU fallback
 * VectorStorage storage = VectorStorageProvider.of("vector")
 *         .dimension(768)
 *         .properties(new VectorStorageProperties()
 *                 .setBackend(VectorStorageProperties.Backend.JVECTOR))
 *         .build();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = "vector", order = 50)
public class VectorStorageProviderFactory implements VectorStorageProvider {

    @Override
    public String name() {
        return "vector";
    }

    @Override
    public VectorStorage create(int dimension, VectorCompareAlgorithm algorithm, Object properties) {
        VectorStorageProperties props = toProperties(properties);
        VectorStorageProperties.Backend backend = props.getBackend();

        if (backend == VectorStorageProperties.Backend.AUTO) {
            backend = detectBackend();
            props.setBackend(backend);
        }

        log.info("[vector-starter] Backend selected: {}, dimension: {}", backend, dimension);

        try {
            return switch (backend) {
                case CUVS -> new CuvsVectorStorage(dimension, algorithm, props);
                case JVECTOR -> new JvectorVectorStorageDelegate(dimension, algorithm, props);
                default -> throw new IllegalArgumentException("Unknown backend: " + backend);
            };
        } catch (Throwable t) {
            if (backend == VectorStorageProperties.Backend.CUVS) {
                log.warn("[vector-starter] cuVS creation failed ({})，falling back to jvector CPU",
                        t.getMessage());
                return new JvectorVectorStorageDelegate(dimension, algorithm, props);
            }
            throw new RuntimeException("向量存储创建失败", t);
        }
    }

    private static VectorStorageProperties toProperties(Object properties) {
        if (properties instanceof VectorStorageProperties props) {
            return props;
        }
        return new VectorStorageProperties();
    }

    /**
     * 尝试检测 cuVS GPU 环境是否可用。
     */
    private static VectorStorageProperties.Backend detectBackend() {
        try {
            com.nvidia.cuvs.CuVSResources resources = com.nvidia.cuvs.CuVSResources.create();
            try {
                int deviceId = resources.deviceId();
                log.info("[vector-starter] cuVS GPU detected, device: {}", deviceId);
                return VectorStorageProperties.Backend.CUVS;
            } finally {
                resources.close();
            }
        } catch (Throwable t) {
            log.info("[vector-starter] cuVS not available ({}：{}），using jvector CPU fallback",
                    t.getClass().getSimpleName(), t.getMessage());
            return VectorStorageProperties.Backend.JVECTOR;
        }
    }
}
