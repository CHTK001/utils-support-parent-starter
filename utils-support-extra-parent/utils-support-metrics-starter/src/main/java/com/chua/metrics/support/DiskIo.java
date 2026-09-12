package com.chua.metrics.support;

import lombok.Data;

/**
 * 磁盘 IO 指标数据模型。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class DiskIo {
    /**
      * 磁盘名称（对应 disk信息.名称）
     */
    private String name;

    /**
     * 读取字节数（自启动以来累计）
     */
    private long readBytes;

    /**
     * 写入字节数（自启动以来累计）
     */
    private long writeBytes;

    /**
     * 读取操作次数（自启动以来累计）
     */
    private long readCount;

    /**
     * 写入操作次数（自启动以来累计）
     */
    private long writeCount;
}