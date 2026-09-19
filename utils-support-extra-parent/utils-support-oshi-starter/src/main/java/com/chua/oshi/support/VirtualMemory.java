package com.chua.oshi.support;

import lombok.Data;

/**
 * 虚拟内存（交换空间/Pagefile）信息实体类。
 * <p>
 * 用于封装系统交换空间/页面文件的总量、已用量、空闲量及使用率等指标。
 *
 * @author CH
 * @since 4.0.0
 */
@Data
public class VirtualMemory {

    /**
     * 交换空间总容量（字节）。
     */
    private long swapTotal;

    /**
     * 交换空间已使用量（字节）。
     */
    private long swapUsed;

    /**
     * 交换空间空闲量（字节）。
     */
    private long swapFree;

    /**
     * 页面文件总容量（字节），窗口 特有。
     */
    private long pagefileTotal;

    /**
     * 页面文件已使用量（字节）。
     */
    private long pagefileUsed;

    /**
     * 页面文件空闲量（字节）。
     */
    private long pagefileFree;

    /**
     * 虚拟内存使用率 [0.0, 1.0]。
     */
    private double swapUsage;

    /**
     * 页面文件使用率 [0.0, 1.0]。
     */
    private double pagefileUsage;
}
