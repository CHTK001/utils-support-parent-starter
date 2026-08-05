package com.chua.runtime.core.service;

import com.chua.common.support.lang.cmd.CmdResult;

import java.nio.file.Path;
import java.util.Map;

/**
 * Java Agent 管理器接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface JavaAgentManager extends AutoCloseable {

    String name();

    Map<Integer, String> listPids();

    CmdResult inspectJvm(int pid);

    CmdResult attach(int pid, Path agentPath, String options);

    CmdResult attachByPort(int port, Path agentPath, String options);

    CmdResult detach(int pid);

    @Override
    default void close() throws Exception {
    }
}