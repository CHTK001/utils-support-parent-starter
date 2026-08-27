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
 * 鍚戦噺瀛樺偍 SPI 宸ュ巶锛岃嚜鍔ㄦ牴鎹繍琛岀幆澧冮€夋嫨 cuVS GPU 鎴?jvector CPU 鍚庣銆? *
 * <p>妫€娴嬮€昏緫锛? * <ul>
 *   <li>鏄惧紡鎸囧畾 backend=CUVS锛氬皾璇曞垱寤?cuVS 璧勬簮锛屽け璐ュ垯闄嶇骇鍒?jvector</li>
 *   <li>鏄惧紡鎸囧畾 backend=JVECTOR锛氱洿鎺ヤ娇鐢?jvector</li>
 *   <li>backend=AUTO锛堥粯璁わ級锛氬皾璇?cuVS锛屽け璐ヨ嚜鍔ㄩ檷绾у埌 jvector</li>
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
            throw new RuntimeException("鍚戦噺瀛樺偍鍒涘缓澶辫触", t);
        }
    }

    /**
     * 杞崲灞炴€у璞★紝null 鎴栫被鍨嬩笉鍖归厤鏃惰繑鍥為粯璁ら厤缃€?     *
     * @param properties 鍘熷灞炴€у璞?     * @return 鍚戦噺瀛樺偍閰嶇疆灞炴€?     */
    private static VectorStorageProperties toProperties(Object properties) {
        if (properties instanceof VectorStorageProperties props) {
            return props;
        }
        return new VectorStorageProperties();
    }

    /**
     * 灏濊瘯妫€娴?cuVS GPU 鐜鏄惁鍙敤锛堥€氳繃鍙嶅皠鍔犺浇 com.nvidia.cuvs.CuVSResources锛夈€?     *
     * @return 鍙敤鐨勫悗绔被鍨嬶紝cuVS 涓嶅彲鐢ㄦ椂杩斿洖 JVECTOR
     */
    private static VectorStorageProperties.Backend detectBackend() {
        try {
            Class<?> resourcesClass = ReflectUtils.forName("com.nvidia.cuvs.CuVSResources");
            if (resourcesClass == null) {
                log.info("[vector-starter] com.nvidia.cuvs classes not found in classpath, using jvector CPU");
                return VectorStorageProperties.Backend.JVECTOR;
            }
            Object resources = ReflectUtils.invokeStatic(resourcesClass, "create", Object.class);
            try {
                int deviceId = (int) ReflectUtils.invoke(resources, "deviceId", int.class);
                log.info("[vector-starter] cuVS GPU detected, device: {}", deviceId);
                return VectorStorageProperties.Backend.CUVS;
            } finally {
                try {
                    ReflectUtils.invoke(resources, "close", Object.class);
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
