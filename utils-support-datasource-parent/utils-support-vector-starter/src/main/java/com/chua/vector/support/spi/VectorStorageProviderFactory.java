package com.chua.vector.support.spi;

import static com.chua.common.support.reflection.ReflectUtils.forName;
import static com.chua.common.support.reflection.ReflectUtils.invoke;
import static com.chua.common.support.reflection.ReflectUtils.invokeStatic;

import com.chua.common.support.reflection.ReflectUtils;
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
                log.warn("[vector-starter] cuVS creation failed ({}), falling back to jvector CPU",
                        t.getMessage());
                return new JvectorVectorStorageDelegate(dimension, algorithm, props);
            }
            throw new RuntimeException("向量存储创建失败", t);
        }
    }

    /**
     * 转换属性对象，null 或类型不匹配时返回默认配置。
     *
     * @param properties 原始属性对象
     * @return 向量存储配置属性
     */
    private static VectorStorageProperties toProperties(Object properties) {
        if (properties instanceof VectorStorageProperties props) {
            return props;
        }
        return new VectorStorageProperties();
    }

    /**
     * 尝试检测 cuVS GPU 环境是否可用（通过反射加载 com.nvidia.cuvs.CuVSResources）。
     *
     * @return 可用的后端类型，cuVS 不可用时返回 JVECTOR
     */
    private static VectorStorageProperties.Backend detectBackend() {
        try {
            Class<?> resourcesClass = forName("com.nvidia.cuvs.CuVSResources");
            if (resourcesClass == null) {
                log.info("[vector-starter] com.nvidia.cuvs classes not found in classpath, using jvector CPU");
                return VectorStorageProperties.Backend.JVECTOR;
            }
            Object resources = invokeStatic(resourcesClass, "create", Object.class);
            try {
                int deviceId = (int) invoke(resources, "deviceId", int.class);
                log.info("[vector-starter] cuVS GPU detected, device: {}", deviceId);
                return VectorStorageProperties.Backend.CUVS;
            } finally {
                try {
                    invoke(resources, "close", Object.class);
                } catch (Throwable ignored) {
                    log.debug("[vector-starter] Failed to close CuVSResources during detection", ignored);
                }
            }
        } catch (Throwable t) {
            log.info("[vector-starter] cuVS unavailable ({}: {}), using jvector CPU fallback",
                    t.getClass().getSimpleName(), t.getMessage());
            return VectorStorageProperties.Backend.JVECTOR;
        }
    }
}
