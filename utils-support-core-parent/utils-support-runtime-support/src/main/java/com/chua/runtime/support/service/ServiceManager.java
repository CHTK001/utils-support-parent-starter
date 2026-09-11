package com.chua.runtime.support.service;

import com.chua.common.support.lang.cmd.CmdResult;

/**
 * 系统服务管理器 SPI 接口 — 将运行时工件注册为操作系统级服务。
 *
 * <p>支持 Windows Service、Linux systemd、Linux init.d 等平台。
 * 通过 SPI 机制自动发现当前平台可用的实现。</p>
 *
 * <p>核心操作：</p>
 * <ul>
 *   <li>{@link #install(ManagedService)} — 安装服务到系统</li>
 *   <li>{@link #uninstall(String)} — 从系统中卸载服务</li>
 *   <li>{@link #start(String)} — 启动服务</li>
 *   <li>{@link #stop(String)} — 停止服务</li>
 *   <li>{@link #restart(String)} — 重启服务</li>
 *   <li>{@link #status(String)} — 查询服务状态</li>
 *   <li>{@link #enable(String)} — 设置开机自启</li>
 *   <li>{@link #disable(String)} — 禁用开机自启</li>
 *   <li>{@link #isEnabled(String)} — 查询是否开机自启</li>
 *   <li>{@link #isInstalled(String)} — 查询服务是否已安装</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ServiceManager extends AutoCloseable {

    /**
     * 获取服务管理器名称。
     *
     * @return 名称标识，如 "windows"、"systemd"
     */
    String name();

    /**
     * 是否支持当前操作系统。
     *
     * @return 支持返回 true
     */
    boolean isSupported();

    /**
     * 安装服务到系统。
     *
     * @param service 服务配置
     * @return 安装结果
     */
    CmdResult install(ManagedService service);

    /**
     * 从系统中卸载服务。
     *
     * @param serviceName 服务名称
     * @return 卸载结果
     */
    CmdResult uninstall(String serviceName);

    /**
     * 启动服务。
     *
     * @param serviceName 服务名称
     * @return 启动结果
     */
    CmdResult start(String serviceName);

    /**
     * 停止服务。
     *
     * @param serviceName 服务名称
     * @return 停止结果
     */
    CmdResult stop(String serviceName);

    /**
     * 重启服务。
     *
     * @param serviceName 服务名称
     * @return 重启结果
     */
    CmdResult restart(String serviceName);

    /**
     * 查询服务状态。
     *
     * @param serviceName 服务名称
     * @return 服务状态查询结果（stdout 包含状态文本）
     */
    CmdResult status(String serviceName);

    /**
     * 设置服务开机自启。
     *
     * @param serviceName 服务名称
     * @return 操作结果
     */
    CmdResult enable(String serviceName);

    /**
     * 禁用服务开机自启。
     *
     * @param serviceName 服务名称
     * @return 操作结果
     */
    CmdResult disable(String serviceName);

    /**
     * 查询服务是否已启用开机自启。
     *
     * @param serviceName 服务名称
     * @return 已启用返回 true
     */
    boolean isEnabled(String serviceName);

    /**
     * 查询服务是否已安装。
     *
     * @param serviceName 服务名称
     * @return 已安装返回 true
     */
    boolean isInstalled(String serviceName);

    @Override
    /** 关闭 */
    default void close() throws Exception {
    }
}