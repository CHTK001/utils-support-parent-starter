package com.chua.vector.support.spi;

import static com.chua.common.support.reflection.ReflectUtils.invoke;
import static com.chua.common.support.reflection.ReflectUtils.invokeStatic;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.vector.RuntimeDetector;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageDescriptor;
import com.chua.common.support.vector.VectorStorageField;
import com.chua.common.support.vector.VectorStorageProvider;
import com.chua.vector.support.configuration.VectorStorageProperties;
import com.chua.vector.support.storage.CuvsVectorStorage;
import com.chua.vector.support.storage.JvectorVectorStorageDelegate;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /**
     * 配置键：后端类型。
     */
    public static final String KEY_BACKEND = "backend";

    /**
     * 配置键：强制 CPU。
     */
    public static final String KEY_FORCE_CPU = "forceCpu";

    /**
     * 配置键：必须有 GPU。
     */
    public static final String KEY_REQUIRE_GPU = "requireGpu";

    @Override
    public String name() {
        return "vector";
    }

    /**
     * 声明本工厂需要的配置项。
     *
     * <p>本 SPI 不是具体向量库，而是「按硬件条件自动选后端」的工厂：有 cuVS 就用 GPU，
     * 否则降级 jvector。因此表单只需暴露后端选择与两个开关，不含任何连接信息。</p>
     *
     * @return 配置描述
     */
    @Override
    public VectorStorageDescriptor descriptor() {
        return new VectorStorageDescriptor("vector", "自动选择（cuVS GPU / JVector CPU）",
                "按硬件自动挑后端：有 NVIDIA GPU 且装 cuVS 就用 GPU，否则用 JVector CPU；"
                        + "不指定时自动探测",
                List.of(
                        VectorStorageField.select(KEY_BACKEND, "后端类型", "AUTO",
                                List.of("AUTO", "CUVS", "JVECTOR"), false,
                                "AUTO 自动探测（优先 GPU）；CUVS 强制 GPU；JVECTOR 强制 CPU"),
                        VectorStorageField.bool(KEY_FORCE_CPU, "强制 CPU", "false",
                                "开启后跳过 GPU 探测，直接用 JVector"),
                        VectorStorageField.bool(KEY_REQUIRE_GPU, "必须有 GPU", "false",
                                "开启后 GPU 不可用直接报错，不降级")),
                false);
    }

    /**
     * 由键值配置构造 {@link VectorStorageProperties}。
     *
     * @param config 键值配置
     * @return 向量存储属性
     */
    @Override
    public Object toProperties(Map<String, Object> config) {
        VectorStorageProperties props = new VectorStorageProperties();
        if (config == null) {
            return props;
        }
        String backend = str(config.get(KEY_BACKEND));
        if (backend != null) {
            try {
                props.backend(VectorStorageProperties.Backend.valueOf(backend.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("vector 不支持的后端类型: " + backend
                        + "，可选: AUTO / CUVS / JVECTOR");
            }
        }
        Boolean forceCpu = boolv(config.get(KEY_FORCE_CPU));
        if (forceCpu != null) {
            props.forceCpu(forceCpu);
        }
        Boolean requireGpu = boolv(config.get(KEY_REQUIRE_GPU));
        if (requireGpu != null) {
            props.requireGpu(requireGpu);
        }
        return props;
    }

    /**
     * 取字符串配置项。
     *
     * @param value 原始值
     * @return 去空白后的字符串；空值返回 null
     */
    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 取布尔配置项。
     *
     * @param value 原始值
     * @return 布尔值；无法解析时返回 null
     */
    private static Boolean boolv(Object value) {
        String s = str(value);
        if (s == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(s) || "1".equals(s) || "on".equalsIgnoreCase(s)
                || "yes".equalsIgnoreCase(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s) || "off".equalsIgnoreCase(s)
                || "no".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }
        return null;
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
     * @param name 名称，不允许为 null
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

    /**
     * 转为属性。
     *
     * @param properties 属性，不允许为 null
     * @return VectorStorage属性 对象
     */
    private static VectorStorageProperties toProperties(Object properties) {
        if (properties instanceof VectorStorageProperties props) {
            return props;
        }
        return new VectorStorageProperties();
    }
}
