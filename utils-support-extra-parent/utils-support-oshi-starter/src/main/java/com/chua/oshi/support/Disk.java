package com.chua.oshi.support;

import lombok.Data;

/**
 * 物理磁盘信息实体类。
 * <p>
 * 封装物理磁盘的型号、容量、旋转速度、健康状态等底层硬件信息。
 * 区别于 SysFile（分区/挂载点级别），Disk 描述的是物理设备本身。
 *
 * @author CH
 */
@Data
public class Disk {

    /**
     * 磁盘控制器类型（HDD / SSD / NVMe / RAID / Unknown）。
     */
    private String controllerType;

    /**
     * 磁盘型号名称。
     */
    private String model;

    /**
     * 磁盘序列号。
     */
    private String serial;

    /**
     * 磁盘总容量（字节）。
     */
    private long size;

    /**
     * 传输速率（MB/s）。
     */
    private long transferSpeed;

    /**
     * 磁盘健康状态（Good / Caution / Bad）。
     */
    private String smartStatus;

    /**
     * 是否为固态硬盘。
     */
    private boolean solidStateDrive;

    /**
     * 旋转速度（RPM），SSD 为 0。
     */
    private int rotationSpeed;
}