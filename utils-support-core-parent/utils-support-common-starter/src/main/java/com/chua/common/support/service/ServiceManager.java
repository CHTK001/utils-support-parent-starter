package com.chua.common.support.service;

import com.chua.common.support.spi.annotations.Spi;

/**
 * SPI 接口：服务生命周期管理器（进程级）。
 *
 * <p>支持 {@code start / stop / restart / status / install / uninstall} 操作。
 * 实现类通过 {@link Spi} 注解指定名称，默认实现为 "process"（本地进程管理）。</p>
 *
 * <h3>命名空间</h3>
 * <ul>
 *   <li>{@code process} — 本地进程管理（默认）</li>
 *   <li>{@code supervisor} — supervisord 管理</li>
 *   <li>{@code systemd} — systemd 管理（Linux）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("service-process")
public interface ServiceManager {

    /**
     * 启动服务，返回进程 PID；未跟踪时返回 -1。
     */
    long start(String jarPath, String startCmd);

    /**
     * 停止服务（按 PID 或进程名）。
     */
    void stop(long pid, String serviceName);

    /**
     * 重启服务。
     */
    void restart(long pid, String serviceName, String jarPath, String startCmd);

    /**
     * 查询服务状态。
     */
    boolean isRunning(long pid);

    /**
     * 安装服务（创建启动脚本、注册为系统服务等，幂等操作）。
     */
    default void install(String serviceName, String jarPath, String startCmd) {
        // 无操作：进程级管理不需要安装步骤
    }

    /**
     * 卸载服务（删除启动脚本、注销系统服务等）。
     */
    default void uninstall(String serviceName) {
        // 无操作
    }
}
