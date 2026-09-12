package com.chua.common.support.service;

import com.chua.common.support.spi.annotations.Spi;

/**
 * SPI 接口：服务进程追踪器。
 *
 * <p>负责从启动命令中提取/生成 PID 文件路径、解析当前 PID、
 * 并通过 PID 文件管理进程生命周期。</p>
 *
 * <h3>命名空间</h3>
 * <ul>
 *   <li>{@code pidfile} — PID 文件追踪（默认）</li>
 *   <li>{@code jps} — 通过 jps 命令查找 Java 进程</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("service-process-tracker")
public interface ServiceProcessTracker {

    /**
     * 启动进程并返回 PID；同时将 PID 写入 PID 文件（如配置）。
     *
     * @param serviceName 服务名称
     * @param startCmd    启动命令（包含 Java -jar ...）
     * @param pidFile     PID 文件路径（可为 空，不使用 PID 文件时）
     * @return 进程 PID
     */
    long startProcess(String serviceName, String startCmd, String pidFile);

    /**
     * 停止指定 PID 的进程（优雅关闭）。
     *
     * @param pid 进程 PID
     */
    void kill(long pid);

    /**
     * 强制终止进程。
     *
     * @param pid 进程 PID
     */
    void killForce(long pid);

    /**
     * 查询进程是否仍在运行。
     *
     * @param pid 进程 PID
     * @return true 表示正在运行
     */
    boolean isRunning(long pid);

    /**
     * 读取 PID 文件中的 PID。
     *
     * @param pidFile PID 文件路径
     * @return PID，文件不存在时返回 -1
     */
    long readPidFromFile(String pidFile);

    /**
     * 写入 PID 到文件。
     *
     * @param pidFile PID 文件路径
     * @param pid     进程 PID
     */
    void writePidToFile(String pidFile, long pid);

    /**
     * 删除 PID 文件。
     *
     * @param pidFile PID 文件路径
     */
    void deletePidFile(String pidFile);

    /**
     * 根据进程名查找 PID（用于未提供 PID 时的回退）。
     *
     * @param serviceName 服务名称或主类名
     * @return 找到的第一个匹配 PID，未找到返回 -1
     */
    long findPidByName(String serviceName);
}
