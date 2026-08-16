package com.chua.common.support.network.sync;

import com.chua.common.support.network.server.SyncServer;

/**
 * 同步流程管理器，负责协调 {@link SyncServer} 与 {@link SyncClient} 的生命周期与数据流转。
 * <p>
 * 提供长连接双向同步能力，支持服务端主动推送和客户端订阅两种模式。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SyncFlow extends AutoCloseable {

    /**
     * 启动同步流程。
     */
    void start();

    /**
     * 停止同步流程。
     */
    void stop();

    /**
     * 判断同步流程是否正在运行。
     *
     * @return true 表示正在运行
     */
    boolean isRunning();

    /**
     * 获取当前关联的同步服务端。
     *
     * @return SyncServer 实例，可能为 null
     */
    SyncServer getServer();

    /**
     * 获取当前关联的同步客户端。
     *
     * @return SyncClient 实例，可能为 null
     */
    SyncClient getClient();

    /**
     * 注册同步事件监听器。
     *
     * @param listener 监听器
     */
    void addListener(SyncFlowListener listener);

    /**
     * 移除同步事件监听器。
     *
     * @param listener 监听器
     */
    void removeListener(SyncFlowListener listener);

    @Override
    void close();
}
