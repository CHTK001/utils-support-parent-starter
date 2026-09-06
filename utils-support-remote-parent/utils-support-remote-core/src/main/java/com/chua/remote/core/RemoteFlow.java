package com.chua.remote.core;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.sync.netty.NettyWebSocketSyncFlow;
import com.chua.remote.core.transport.RemoteTransport;
import lombok.extern.slf4j.Slf4j;

/**
 * 远控流程管理器。
 *
 * <p>同时管理网关服务端和客户端，适用于网关既作为服务端接收被控端连接，
 * 又作为客户端连接控制端的场景。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RemoteFlow {

    /** 流程 */
    private final NettyWebSocketSyncFlow flow;

    /**
     * 创建流程管理器（纯客户端模式）。
     *
     * @param serverUrl 服务端地址
     */
    public RemoteFlow(String serverUrl) {
        this.flow = new NettyWebSocketSyncFlow(serverUrl);
    }

    /**
     * 创建流程管理器（服务端模式）。
     *
     * @param setting 服务配置
     */
    public RemoteFlow(ServerSetting setting) {
        this.flow = new NettyWebSocketSyncFlow(setting);
    }

    /**
     * 创建流程管理器（同时启动服务端和客户端）。
     *
     * @param setting    服务配置
     * @param serverUrl  客户端连接地址
     */
    public RemoteFlow(ServerSetting setting, String serverUrl) {
        this.flow = new NettyWebSocketSyncFlow(setting, serverUrl);
    }

    /**
     * 启动流程。
     */
    public void start() {
        flow.start();
        log.info("远控流程已启动");
    }

    /**
     * 停止流程。
     */
    public void stop() {
        flow.stop();
        log.info("远控流程已停止");
    }

    /**
     * 获取底层 Flow。
     *
     * @return Flow
     */
    public NettyWebSocketSyncFlow getFlow() {
        return flow;
    }
}
