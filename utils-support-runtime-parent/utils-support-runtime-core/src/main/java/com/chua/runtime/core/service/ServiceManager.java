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

    String name();

    boolean isSupported();

    CmdResult install(ManagedService service);

    CmdResult uninstall(String serviceName);

    CmdResult start(String serviceName);

    CmdResult stop(String serviceName);

    CmdResult restart(String serviceName);

    CmdResult status(String serviceName);

    CmdResult enable(String serviceName);

    CmdResult disable(String serviceName);

    boolean isEnabled(String serviceName);

    boolean isInstalled(String serviceName);

    @Override
    /** 关闭 */
    default void close() throws Exception {
    }
}