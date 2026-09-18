package com.chua.metrics.support;

import lombok.Data;

/**
* 磁盘信息指标数据模型。
*
* @author CH
* @since 4.0.0.42
 */
@Data
public class DiskInfo {
    /**
    * 磁盘名称（如 sda、nvme0n1 等）
    */
    private String name;

    /**
    * 挂载点路径（如 /、/Home 等）
    */
    private String mountPoint;

    /**
    * 磁盘总容量（字节）
    */
    private long total;

    /**
    * 已用容量（字节）
    */
    private long used;

    /**
    * 可用容量（字节）
    */
    private long available;

    /**
    * 文件系统类型（如 ext4、ntfs、apfs 等）
    */
    private String fileSystem;
}
