package com.chua.metrics.support;

import lombok.Data;

/**
 * 进程指标数据模型。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class ProcessInfo {
    /**
      * 进程 标识（PID）
     */
    private int pid;

    /**
     * 进程名称
     */
    private String name;

    /**
     * CPU 使用率（百分比，0-100）
     */
    private float cpuUsage;

    /**
     * 内存使用量（字节）
     */
    private long memoryBytes;

    /**
     * 线程数量
     */
    private int threadCount;
}