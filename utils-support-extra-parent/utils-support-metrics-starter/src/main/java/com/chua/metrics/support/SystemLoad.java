package com.chua.metrics.support;

import lombok.Data;

/**
 * 系统负载指标数据模型。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class SystemLoad {
    /**
     * 1 分钟负载平均值
     */
    private double load1;

    /**
     * 5 分钟负载平均值
     */
    private double load5;

    /**
     * 15 分钟负载平均值
     */
    private double load15;
}
