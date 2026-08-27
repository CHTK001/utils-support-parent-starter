package com.chua.vector.support.spi;

import static com.chua.common.support.reflection.ReflectUtils.invoke;
import static com.chua.common.support.reflection.ReflectUtils.invokeStatic;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.vector.RuntimeDetector;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageProvider;
import com.chua.vector.support.configuration.VectorStorageProperties;
import com.chua.vector.support.storage.CuvsVectorStorage;
import com.chua.vector.support.storage.JvectorVectorStorageDelegate;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 向量存储 SPI 工厂，通过 {@link RuntimeDetector} SPI 自动选择最优后端。
 *
 * <p>检测流程：
 * <ol>
 *   <li>收集所有已注册的 {@link RuntimeDetector}（cuvs、jvector、memory 等）</li>
 *   <li>按优先级降序排列，依次检查 {@link RuntimeDetector#isAvailable()}</li>
 *   <li>选择第一个可用的后端，未指定时自动降级</li>
 * </ol>
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
            backend = selectBestBackend();
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
     * 通过 RuntimeDetector SPI 选择最优后端。
     *
     * @return 选中的后端类型
     */
    @SuppressWarnings("unchecked")
    private static VectorStorageProperties.Backend selectBestBackend() {
        List<RuntimeDetector> detectors = ServiceProvider.of(RuntimeDetector.class).collect();
        if (detectors.isEmpty()) {
            log.info("[vector-starter] No RuntimeDetector found, using jvector CPU");
            return VectorStorageProperties.Backend.JVECTOR;
        }
        detectors.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        for (RuntimeDetector detector : detectors) {
            if (detector.isAvailable()) {
                log.info("[vector-starter] Selected backend: {} (priority: {})", detector.name(), detector.priority());
                return switch (detector.name()) {
                    case "cuvs" -> VectorStorageProperties.Backend.CUVS;
                    case "jvector" -> VectorStorageProperties.Backend.JVECTOR;
                    default -> VectorStorageProperties.Backend.JVECTOR;
                };
            }
        }
        log.info("[vector-starter] No backend available, using jvector CPU");
        return VectorStorageProperties.Backend.JVECTOR;
    }

    private static VectorStorageProperties toProperties(Object properties) {
        if (properties instanceof VectorStorageProperties props) {
            return props;
        }
        return new VectorStorageProperties();
    }
}
