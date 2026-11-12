package com.chua.common.support.nativeapi;

/**
 * 系统指标采样原生库 SPI。
 *
 * <p>由 {@code utils-support-native-metrics} 提供实现，对应
 * {@code metrics_native} 的 start / poll / stop 三个原语。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface MetricsNativeSpi extends NativeService {

    /**
     * 启动采样器。
     *
     * @param intervalMs 采样间隔（毫秒）
     */
    void start(long intervalMs);

    /**
     * 拉取当前快照。
     *
     * @return JSON 快照；未启动或拉取失败返回 {@code null}
     */
    String poll();

    /**
     * 停止采样器（幂等）。
     */
    void close();
}
