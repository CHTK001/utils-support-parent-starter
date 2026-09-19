package com.chua.metrics.support;

import lombok.Data;

/**
 * GPU 信息指标数据模型。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class GpuInfo {
    /**
     * GPU 索引（从 0 开始）
     */
    private int id;

    /**
     * GPU 名称/型号
     */
    private String name;

    /**
     * GPU 使用率（百分比，0-100）
     */
    private float usage;

    /**
     * 已用显存容量（字节）
     */
    private long memoryUsed;

    /**
     * 总显存容量（字节）
     */
    private long memoryTotal;

    /**
     * GPU 温度（摄氏度，可选）
     */
    private Float temperature;
}
