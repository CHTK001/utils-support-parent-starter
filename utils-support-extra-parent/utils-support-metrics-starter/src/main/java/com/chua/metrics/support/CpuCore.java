package com.chua.metrics.support;

import lombok.Data;

/**
* CPU 核心指标数据模型。
*
* @author CH
* @since 4.0.0.42
 */
@Data
public class CpuCore {
    /**
    * 核心 标识（从 0 开始）
    */
    private int id;

    /**
    * CPU 使用率（百分比，0-100）
    */
    private float usage;

    /**
    * CPU 频率（Hz）
    */
    private long frequency;

    /**
    * CPU 名称/型号
    */
    private String name;
}
