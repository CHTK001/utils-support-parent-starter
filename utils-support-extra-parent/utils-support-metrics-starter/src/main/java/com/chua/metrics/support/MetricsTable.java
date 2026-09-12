package com.chua.metrics.support;

import lombok.Data;

/**
* 指标表格数据模型，用于表格化展示单个指标项。
*
* @author CH
* @since 4.0.0.42
 */
@Data
public class MetricsTable {
    /**
    * 时间戳（Unix 时间戳，毫秒）
     */
    private long timestamp;

    /**
    * 表格名称（对应指标类型，如 cpu、内存、disk 等）
     */
    private String table;
}