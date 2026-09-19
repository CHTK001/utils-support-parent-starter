package com.chua.oshi.support;

import lombok.Data;

/**
 * 磁盘分区信息实体类。
 * <p>
 * 描述物理磁盘上的分区，区别于 sys文件（文件系统挂载点级别）。
 *
 * @author CH
 * @since 4.0.0
 */
@Data
public class HWPartition {

    /**
     * 分区名称。
     */
    private String name;

    /**
     * 分区设备路径（如 /dev/sda1）。
     */
    private String device;

    /**
     * 分区类型（MBR/GPT 标识符）。
     */
    private String type;

    /**
     * 分区偏移量（字节）。
     */
    private long offset;

    /**
     * 分区大小（字节）。
     */
    private long size;

    /**
     * 父磁盘名称。
     */
    private String parentName;
}
