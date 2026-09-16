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

import java.util.ArrayList;
import java.util.List;

/**
* 向量存储 SPI 工厂，通过 {@link RuntimeDetector} SPI 自动选择最优后端。
*
* <h3>后端选择优先级</h3>
* <pre>
* forceCpu=true                    → jvector (CPU)
* forceCpu=false, requireGpu=true  → cuVS 可用则用，否则抛异常
* forceCpu=false, requireGpu=false → cuVS 可用则用，否则降级到 jvector (CPU)
* </pre>
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
        VectorStorageProperties.Backend backend = resolveBackend(props);
        props.backend(backend);

        log.info("[vector-starter] Backend: {}, dimension: {}, forceCpu: {}, requireGpu: {}",
                backend, dimension, props.forceCpu(), props.requireGpu());

        try {
            return switch (backend) {
                case CUVS -> new CuvsVectorStorage(dimension, algorithm, props);
                case JVECTOR -> new JvectorVectorStorageDelegate(dimension, algorithm, props);
                default -> throw new IllegalStateException("Unsupported backend: " + backend);
            };
        } catch (Throwable t) {
            if (backend == VectorStorageProperties.Backend.CUVS) {
                log.warn("[vector-starter] cuVS creation failed ({}), falling back to jvector CPU",
                        t.getMessage());
                if (props.requireGpu()) {
                    throw new RuntimeException("cuVS GPU 不可用且 requireGpu=true", t);
                }
                return new JvectorVectorStorageDelegate(dimension, algorithm, props);
            }
            throw new RuntimeException("向量存储创建失败", t);
        }
    }

    /**
    * 根据配置和运行时环境解析最终使用的后端。
    *
    * @param props 用户配置
    * @return 实际使用的后端类型
     */
    private VectorStorageProperties.Backend resolveBackend(VectorStorageProperties props) {
        // forceCpu=true：直接走 CPU，跳过 GPU 检测
        if (props.forceCpu()) {
            log.info("[vector-starter] forceCpu=true, skipping GPU detection, using jvector");
            return VectorStorageProperties.Backend.JVECTOR;
        }

        // 显式指定 backend
        VectorStorageProperties.Backend specified = props.backend();
        if (specified == VectorStorageProperties.Backend.CUVS) {
            return tryOrFallback(props, "cuvs");
        }
        if (specified == VectorStorageProperties.Backend.JVECTOR) {
            return VectorStorageProperties.Backend.JVECTOR;
        }

        // requireGpu=true：尝试 GPU，失败抛异常
        if (props.requireGpu()) {
            return selectGpuOrThrow(props);
        }

        // AUTO（默认）：优先 GPU，失败降级 CPU
        return selectBestBackend(props);
    }

    /**
    * 尝试选择 cuvs，不可用时降级到 jvector 并记录日志。
    * @param props props
    * @param backendName backend名称
    * @return 尝试或降级的结果
     */
    private VectorStorageProperties.Backend tryOrFallback(VectorStorageProperties props, String backendName) {
        try {
            VectorStorageProperties.Backend selected = selectBackend(backendName);
            if (selected == VectorStorageProperties.Backend.CUVS) {
                return selected;
            }
        } catch (Throwable t) {
            log.warn("[vector-starter] {} unavailable: {} ({})",
                    backendName, t.getMessage(), t.getClass().getSimpleName());
            if (props.requireGpu()) {
                throw new RuntimeException(backendName + " GPU 不可用且 requireGpu=true", t);
            }
        }
        log.info("[vector-starter] Falling back to jvector CPU");
        return VectorStorageProperties.Backend.JVECTOR;
    }

    /**
    * 尝试获取 GPU，不可用时抛出异常（requiregpu=true 场景）。
    * @param props props
    * @return 选择gpu或抛出的结果
     */
    private VectorStorageProperties.Backend selectGpuOrThrow(VectorStorageProperties props) {
        VectorStorageProperties.Backend backend = selectBestBackend(props);
        if (backend == VectorStorageProperties.Backend.CUVS) {
            return backend;
        }
        throw new RuntimeException(
                "GPU 不可用且 requireGpu=true。安装指南：\n"
                + "  1. 安装 NVIDIA 驱动: https://www.nvidia.com/Download/index.aspx\n"
                + "  2. 安装 CUDA 12.x: https://developer.nvidia.com/cuda-downloads\n"
                + "  3. 安装 cuVS Java API: conda install -c rapidsai -c conda-forge libcuvs cuda-version=12.9\n"
                + "  或从源码构建: cd cuvs/java && ./build.sh && mvn install");
    }

    /**
    * 通过 runtimedetector SPI 选择最优后端。
    *
    * @return 选中的后端类型
    * @param props props
     */
    @SuppressWarnings("unchecked")
    private VectorStorageProperties.Backend selectBestBackend(VectorStorageProperties props) {
        List<RuntimeDetector> detectors = new ArrayList<>(ServiceProvider.of(RuntimeDetector.class).collect());
        if (detectors.isEmpty()) {
            log.info("[vector-starter] No RuntimeDetector found, using jvector CPU");
            return VectorStorageProperties.Backend.JVECTOR;
        }
        detectors.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        for (RuntimeDetector detector : detectors) {
            if (detector.isAvailable()) {
                log.info("[vector-starter] Selected backend: {} (priority: {})",
                        detector.name(), detector.priority());
                return switch (detector.name()) {
                    case "cuvs" -> VectorStorageProperties.Backend.CUVS;
                    default -> VectorStorageProperties.Backend.JVECTOR;
                };
            }
        }
        log.info("[vector-starter] No backend available, using jvector CPU");
        return VectorStorageProperties.Backend.JVECTOR;
    }

    /**
     * 根据名称选择对应后端（内部方法，不抛异常）。
     *
     * @param properties 属性
     * @return 转为属性的结果
     */
    @SuppressWarnings("unchecked")
    private VectorStorageProperties.Backend selectBackend(String name) {
        List<RuntimeDetector> detectors = new ArrayList<>(ServiceProvider.of(RuntimeDetector.class).collect());
        for (RuntimeDetector d : detectors) {
            if (name.equals(d.name()) && d.isAvailable()) {
                return switch (name) {
                    case "cuvs" -> VectorStorageProperties.Backend.CUVS;
                    default -> VectorStorageProperties.Backend.JVECTOR;
                };
            }
        }
        return VectorStorageProperties.Backend.JVECTOR;
    }

    private static VectorStorageProperties toProperties(Object properties) {
        if (properties instanceof VectorStorageProperties props) {
            return props;
        }
        return new VectorStorageProperties();
    }
}
