package com.chua.runtime.core.service;

import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.runtime.core.model.ManagedService;

/**
 * 系统服务管理器接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ServiceManager extends AutoCloseable {

    /**
     * 名称。
     *
     * @return 结果字符串
     */
    String name();

    /**
     * 是否Supported。
     *
     * @return 是否成功（true 表示成功）
     */
    boolean isSupported();

    /**
     * install。
     *
     * @param service 服务，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult install(ManagedService service);

    /**
     * uninstall。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult uninstall(String serviceName);

    /**
     * 启动。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult start(String serviceName);

    /**
     * 停止。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult stop(String serviceName);

    /**
     * restart。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult restart(String serviceName);

    /**
     * 状态。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult status(String serviceName);

    /**
     * enable。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult enable(String serviceName);

    /**
     * disable。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult disable(String serviceName);

    /**
     * 是否Enabled。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    boolean isEnabled(String serviceName);

    /**
     * 是否Installed。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    boolean isInstalled(String serviceName);

    @Override
    /**
     * 关闭
    */
    default void close() throws Exception {
    }
}