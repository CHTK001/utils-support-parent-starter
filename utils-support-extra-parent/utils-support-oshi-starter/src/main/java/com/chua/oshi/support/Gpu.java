package com.chua.oshi.support;

import lombok.Data;

/**
 * 显卡/GPU 信息实体类。
 *
 * @author CH
 * @since 4.0.0
 */
@Data
public class Gpu {

    /**
      * 显卡名称（如 NVIDIA geforce RTX 4090）。
     */
    private String name;

    /**
     * 显卡厂商。
     */
    private String vendor;

    /**
     * 显卡驱动版本。
     */
    private String driverVersion;

    /**
     * 显存总量（字节）。
     */
    private long vram;

    /**
     * 当前 GPU 利用率（百分比 0-100）。
     */
    private double gpuUsage;

    /**
     * 显卡温度（摄氏度）。
     */
    private double temperature;
}