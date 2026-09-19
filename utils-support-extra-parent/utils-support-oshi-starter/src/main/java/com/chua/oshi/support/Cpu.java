package com.chua.oshi.support;

import lombok.Data;

/**
 * CPU 信息实体类。
 * <p>
 * 用于封装 CPU 的使用率统计信息，包括用户态、系统态、等待时间及空闲时间等。
 * 所有使用率字段取值范围为 [0.0, 100.0]，表示百分比。
 *
 * @author CH
 * @since 4.0.0
 */
@Data
public class Cpu {

    /**
     * 用户态 CPU 使用率（百分比 0-100）。
     */
    private double user;

    /**
     * 系统态 CPU 使用率（百分比 0-100）。
     */
    private double sys;

    /**
     * I/O 等待 CPU 使用率（百分比 0-100）。
     */
    private double wait;

    /**
     * CPU 空闲率（百分比 0-100）。
     */
    private double free;

    /**
     * CPU 总使用率（百分比 0-100）。
     */
    private double used;

    /**
     * CPU 逻辑核心数量。
     */
    private int cpuNum;
}
