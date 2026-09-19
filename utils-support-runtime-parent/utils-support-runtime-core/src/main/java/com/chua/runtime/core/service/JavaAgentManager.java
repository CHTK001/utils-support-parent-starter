package com.chua.runtime.core.service;

import com.chua.common.support.lang.cmd.CmdResult;

import java.nio.file.Path;
import java.util.Map;

/**
 * Java 智能体 管理器接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface JavaAgentManager extends AutoCloseable {

    /**
     * 名称。
     *
     * @return 结果字符串
     */
    String name();

    /**
     * 列出Pids。
     *
     * @return 结果映射，无数据时为空映射
     */
    Map<Integer, String> listPids();

    /**
     * inspectJvm。
     *
     * @param pid 方法入参 pid
     * @return Cmd结果 对象
     */
    CmdResult inspectJvm(int pid);

    /**
     * 挂载。
     *
     * @param pid 方法入参 pid
     * @param agentPath agent路径，不允许为 null
     * @param options 选项，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult attach(int pid, Path agentPath, String options);

    /**
     * 挂载By端口。
     *
     * @param port 端口，不允许为 null
     * @param agentPath agent路径，不允许为 null
     * @param options 选项，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult attachByPort(int port, Path agentPath, String options);

    /**
     * 卸载。
     *
     * @param pid 方法入参 pid
     * @return Cmd结果 对象
     */
    CmdResult detach(int pid);

    @Override
    /**
     * 关闭
    */
    default void close() throws Exception {
    }
}