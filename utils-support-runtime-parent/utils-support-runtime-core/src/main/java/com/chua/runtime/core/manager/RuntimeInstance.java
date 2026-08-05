package com.chua.runtime.core.manager;

import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.runtime.core.manager.RuntimeManager;
import com.chua.runtime.core.model.LogStream;
import com.chua.runtime.core.model.RuntimeArtifact;
import com.chua.runtime.core.model.RuntimeStatus;

import java.util.concurrent.CompletableFuture;

/**
 * 运行时实例接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RuntimeInstance extends AutoCloseable {

    RuntimeArtifact artifact();
    RuntimeStatus status();
    long pid();
    CmdResult start();
    CmdResult stop();
    CmdResult restart();
    CmdResult healthCheck();
    LogStream logStream();
    CompletableFuture<CmdResult> onExit();
}