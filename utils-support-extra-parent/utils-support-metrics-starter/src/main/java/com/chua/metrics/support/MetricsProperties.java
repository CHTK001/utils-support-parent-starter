package com.chua.metrics.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Metrics 配置属性。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@ConfigurationProperties(prefix = "metrics")
public class MetricsProperties {

    /**
     * 是否启用 metrics 模块
     */
    private boolean enabled = true;

    /**
     * native 内部采样间隔（毫秒）
     */
    private long intervalMs = 1000L;

    /**
     * 是否启用 CPU 指标采集
     */
    private boolean enableCpu = true;

    /**
     * 是否启用内存指标采集
     */
    private boolean enableMemory = true;

    /**
     * 是否启用磁盘指标采集
     */
    private boolean enableDisk = true;

    /**
     * 是否启用网络指标采集
     */
    private boolean enableNetwork = true;

    /**
     * 是否启用进程指标采集
     */
    private boolean enableProcess = false;

    /**
     * 是否启用 GPU 指标采集
     */
    private boolean enableGpu = true;

    /**
     * 是否启用电池指标采集
     */
    private boolean enableBattery = true;

    /**
     * 是否启用系统负载采集
     */
    private boolean enableLoad = true;
}