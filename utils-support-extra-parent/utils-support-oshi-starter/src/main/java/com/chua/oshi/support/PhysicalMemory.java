package com.chua.oshi.support;

import lombok.Data;

/**
* 物理内存条信息实体类。
* <p>
* 描述单根内存条的详细信息，区别于 Mem 的全局内存汇总。
*
* @author CH
* @since 4.0.0
 */
@Data
public class PhysicalMemory {

    /**
    * 内存条容量（字节）。
     */
    private long capacity;

    /**
    * 内存类型（DDR3/DDR4/DDR5/HBM 等）。
     */
    private String memoryType;

    /**
    * 内存频率（mhz）。
     */
    private long clockSpeed;

    /**
    * 制造商名称。
     */
    private String manufacturer;

    /**
    * 制造商 标识。
     */
    private String manufacturerId;

    /**
    * 设备定位器（如 DIMM_1）。
     */
    private String deviceLocator;

    /**
    * 内存条序列号。
     */
    private String serialNumber;

    /**
    * 配置容量（字节），可能小于实际容量（因配置限制）。
     */
    private long configuredClockSpeed;

    /**
    * 最小电压（伏特）。
     */
    private int minVoltage;

    /**
    * 最大电压（伏特）。
     */
    private int maxVoltage;
}