package com.chua.runtime.support;

import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.runtime.support.model.LogStream;
import com.chua.runtime.support.model.RuntimeArtifact;
import com.chua.runtime.support.model.RuntimeStatus;

import java.util.concurrent.CompletableFuture;

/**
 * 运行时实例 — 管理单个工件的生命周期。
 *
 * <p>每个 {@link RuntimeInstance} 对应一个已启动的进程，提供启动、停止、重启、
 * 状态查询和实时日志等操作。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RuntimeInstance extends AutoCloseable {

    /**
     * 获取关联的工件信息。
     *
     * @return 工件描述
     */
    RuntimeArtifact artifact();

    /**
     * 获取当前运行时状态。
     *
     * @return 运行时状态
     */
    RuntimeStatus status();

    /**
     * 获取进程 ID（仅 RUNNING 状态有效）。
     *
     * @return 进程 ID，未运行时返回 -1
     */
    long pid();

    /**
     * 启动进程。
     *
     * <p>同步等待启动完成或超时，返回包含启动结果的 CmdResult。</p>
     *
     * @return 启动结果
     */
    CmdResult start();

    /**
     * 停止进程。
     *
     * <p>先尝试优雅停止（SIGTERM/Ctrl+C），超时后强制终止。</p>
     *
     * @return 停止结果
     */
    CmdResult stop();

    /**
     * 重启进程。
     *
     * <p>先停止再启动，保持相同的工件配置。</p>
     *
     * @return 重启结果
     */
    CmdResult restart();

    /**
     * 执行健康检查。
     *
     * <p>根据工件配置的 healthCheckUrl 或 healthCheckCommand 执行检查。</p>
     *
     * @return 健康检查结果（isSuccess() 表示健康）
     */
    CmdResult healthCheck();

    /**
     * 获取实时日志流。
     *
     * @return 日志流实例
     */
    LogStream logStream();

    /**
     * 获取进程退出时的 Future。
     *
     * <p>可用于等待进程自然退出。</p>
     *
     * @return CompletableFuture，进程退出时完成
     */
    CompletableFuture<CmdResult> onExit();
}