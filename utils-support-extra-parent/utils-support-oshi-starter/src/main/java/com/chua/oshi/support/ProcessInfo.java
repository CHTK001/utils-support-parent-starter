package com.chua.oshi.support;

import lombok.Data;

/**
 * 进程信息实体类。
 *
 * @author CH
 * @since 4.0.0
 */
@Data
public class ProcessInfo {

    /**
     * 进程 标识。
     */
    private int pid;

    /**
     * 父进程 标识。
     */
    private int parentPid;

    /**
     * 进程名称。
     */
    private String name;

    /**
     * 进程用户。
     */
    private String user;

    /**
     * 进程 CPU 使用率（百分比 0-100）。
     */
    private double cpuUsage;

    /**
     * 进程内存使用量（字节）。
     */
    private long memUsage;

    /**
     * 进程运行时间（毫秒）。
     */
    private long upTime;

    /**
     * 进程状态（RUNNING / SLEEPING / STOPPED / ZOMBIE）。
     */
    private String state;

    /**
     * 启动命令。
     */
    private String commandLine;
}
