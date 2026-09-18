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

    /**
     * artifact。
     *
     * @return RuntimeArtifact 对象
     */
    RuntimeArtifact artifact();
    /**
     * 状态。
     *
     * @return Runtime状态 对象
     */
    RuntimeStatus status();
    /**
     * pid。
     *
     * @return 结果数值
     */
    long pid();
    /**
     * 启动。
     *
     * @return Cmd结果 对象
     */
    CmdResult start();
    /**
     * 停止。
     *
     * @return Cmd结果 对象
     */
    CmdResult stop();
    /**
     * restart。
     *
     * @return Cmd结果 对象
     */
    CmdResult restart();
    /**
     * health校验。
     *
     * @return Cmd结果 对象
     */
    CmdResult healthCheck();
    /**
     * log流。
     *
     * @return Log流 对象
     */
    LogStream logStream();
    /**
     * 响应Exit。
     *
     * @return CompletableFuture 对象
     */
    CompletableFuture<CmdResult> onExit();
}