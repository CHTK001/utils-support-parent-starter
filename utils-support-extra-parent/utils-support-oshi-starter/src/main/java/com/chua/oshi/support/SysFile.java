package com.chua.oshi.support;

import lombok.Data;

/**
 * 系统文件信息实体类，用于存储磁盘分区的详细信息。
 * <p>
 * 所有容量单位为字节（Byte），使用率为 [0.0, 1.0] 的小数。
 *
 * @author CH
 */
@Data
public class SysFile {

    /**
     * 目录名称（挂载点路径）。
     */
    private String dirName;

    /**
     * 文件系统类型名称（如 NTFS, ext4, APFS 等）。
     */
    private String typeName;

    /**
     * 总容量（字节）。
     */
    private long total;

    /**
     * 已用容量（字节）。
     */
    private long used;

    /**
     * 剩余容量（字节）。
     */
    private long free;

    /**
     * 使用率，取值范围 [0.0, 1.0]。
     */
    private double usage;
}
