package com.chua.metrics.support;

import lombok.Data;

/**
* 电池信息指标数据模型（仅移动设备/笔记本适用）。
*
* @author CH
* @since 4.0.0.42
 */
@Data
public class BatteryInfo {
    /**
    * 电池索引（从 0 开始）
     */
    private int index;

    /**
    * 剩余电量百分比（0-100）
     */
    private int chargePercent;

    /**
    * 电池状态（charging/discharging/完整/unknown 等）
     */
    private String status;

    /**
    * 预计剩余使用时间（分钟，可选，仅在 discharging 状态下有效）
     */
    private Integer timeRemainingMinutes;

    /**
    * 是否连接外部电源
     */
    private boolean pluggedIn;
}