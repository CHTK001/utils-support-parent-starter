package com.chua.metrics.support;

import com.chua.common.support.nativeapi.MetricsNativeSpi;
import com.chua.common.support.nativeapi.NativeServices;

/**
 * 指标原生库门面。
 *
 * <p>实现由 {@code utils-support-native-metrics} 通过 SPI 注册；本类只依赖
 * {@link MetricsNativeSpi} 契约，不再反射 native 类。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
final class MetricsNativeLibrary {

    private final MetricsNativeSpi delegate;

    private MetricsNativeLibrary(MetricsNativeSpi delegate) {
        this.delegate = delegate;
    }

    /**
     * 查找可用的指标原生实现。
     *
     * @return 门面实例；未引入 native 制品或加载失败时返回 {@code null}
     */
    static MetricsNativeLibrary create() {
        MetricsNativeSpi spi = NativeServices.get(MetricsNativeSpi.class);
        return spi == null ? null : new MetricsNativeLibrary(spi);
    }

    /**
     * 启动采样器。
     *
     * @param intervalMs 采样间隔（毫秒）
     */
    void start(long intervalMs) {
        delegate.start(intervalMs);
    }

    /**
     * 拉取当前快照。
     *
     * @return JSON 快照
     */
    String poll() {
        return delegate.poll();
    }

    /**
     * 停止采样器。
     */
    void close() {
        delegate.close();
    }
}
