package com.chua.oshi.support;

import lombok.Data;

/**
 * 电源/电池信息实体类。
 * <p>
 * 主要用于笔记本电脑的电池监控，桌面端可能无数据。
 *
 * @author CH
 * @since 4.0.0
 */
@Data
public class Battery {

    /**
     * 电池名称（如 "Primary Battery"）。
     */
    private String name;

    /**
     * 电池剩余电量百分比（0.0 - 100.0）。
     */
    private double capacity;

    /**
     * 电池当前容量（Wh）。
     */
    private double currentCapacity;

    /**
     * 电池最大容量（Wh）。
     */
    private double maxCapacity;

    /**
     * 状态（Charging / Discharging / 完整 / Unknown）。
     */
    private String state;

    /**
     * 预计剩余时间（秒），-1 表示未知。
     */
    private long timeRemaining;
}
