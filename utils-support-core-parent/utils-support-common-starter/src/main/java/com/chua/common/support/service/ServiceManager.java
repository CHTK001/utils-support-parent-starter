package com.chua.common.support.service;

import com.chua.common.support.spi.annotations.Spi;

/**
 * SPI 接口：服务生命周期管理器（进程级）。
 *
 * <p>支持 {@code start / stop / restart / status / install / uninstall} 操作。
 * 实现类通过 {@link Spi} 注解指定名称，默认实现为 "处理"（本地进程管理）。</p>
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
    *
    * @param jarPath  jar 文件路径
    * @param startCmd 启动命令（含 Java -jar 等）
    * @return 进程 PID，启动失败返回 -1
    */
    long start(String jarPath, String startCmd);

    /**
    * 启动服务，返回进程 PID，同时写入 PID 文件。
    *
    * @param jarPath  jar 文件路径
    * @param startCmd 启动命令
    * @param pidFile  PID 文件路径，空 则使用默认路径
    * @return 进程 PID，启动失败返回 -1
    */
    long start(String jarPath, String startCmd, String pidFile);

    /**
    * 停止服务（按 PID 或进程名）。
    * @param pid 方法入参 pid
    * @param serviceName 服务名称，不允许为 null
    */
    void stop(long pid, String serviceName);

    /**
    * 重启服务。
    * @param pid 方法入参 pid
    * @param serviceName 服务名称，不允许为 null
    * @param jarPath jar路径，不允许为 null
    * @param startCmd 启动Cmd，不允许为 null
    */
    void restart(long pid, String serviceName, String jarPath, String startCmd);

    /**
    * 查询服务状态。
    *
    * @param pid 进程 PID，-1 时按 服务名称 查找
    * @return true 表示正在运行
    */
    boolean isRunning(long pid);

    /**
    * 按服务名称查找 PID（供 状态 查询使用）。
    *
    * @param serviceName 服务名称
    * @return 进程 PID，未找到返回 -1
    */
    default long findPidByName(String serviceName) {
        return -1;
    }

    /**
    * 安装服务（创建启动脚本、注册为系统服务等，幂等操作）。
    * @param serviceName 服务名称，不允许为 null
    * @param jarPath jar路径，不允许为 null
    * @param startCmd 启动Cmd，不允许为 null
    */
    default void install(String serviceName, String jarPath, String startCmd) {
        // 无操作：进程级管理不需要安装步骤
    }

    /**
    * 卸载服务（删除启动脚本、注销系统服务等）。
    * @param serviceName 服务名称，不允许为 null
    */
    default void uninstall(String serviceName) {
        // 无操作
    }
}
