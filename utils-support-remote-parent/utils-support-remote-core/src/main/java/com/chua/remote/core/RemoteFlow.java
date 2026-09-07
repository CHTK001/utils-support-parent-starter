package com.chua.remote.core;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.remote.core.transport.FrameClient;
import com.chua.remote.core.transport.FrameServer;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * 远控流程管理器。
 *
 * <p>同时管理网关服务端和客户端（零拷贝帧引擎），适用于网关既作为
 * 服务端接收被控端连接，又作为客户端连接上级网关的场景。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RemoteFlow {

    /** 服务端引擎（服务端模式） */
    private final FrameServer server;

    /** 客户端引擎（客户端模式） */
    private final FrameClient client;

    /**
     * 创建流程管理器（纯客户端模式，随机连接标识）。
     *
     * @param serverUrl 服务端地址
     */
    public RemoteFlow(String serverUrl) {
        this.server = null;
        this.client = new FrameClient(UUID.randomUUID().toString(), serverUrl);
    }

    /**
     * 创建流程管理器（纯客户端模式，显式连接标识）。
     *
     * @param clientId  连接标识
     * @param serverUrl 服务端地址
     */
    public RemoteFlow(String clientId, String serverUrl) {
        this.server = null;
        this.client = new FrameClient(clientId, serverUrl);
    }

    /**
     * 创建流程管理器（服务端模式）。
     *
     * @param setting 服务配置
     */
    public RemoteFlow(ServerSetting setting) {
        this.server = new FrameServer(setting);
        this.client = null;
    }

    /**
     * 创建流程管理器（同时启动服务端和客户端）。
     *
     * @param setting   服务配置
     * @param serverUrl 客户端连接地址
     */
    public RemoteFlow(ServerSetting setting, String serverUrl) {
        this.server = new FrameServer(setting);
        this.client = new FrameClient(UUID.randomUUID().toString(), serverUrl);
    }

    /**
     * 启动流程。
     */
    public void start() {
        if (server != null) {
            server.start();
        }
        if (client != null) {
            client.connect();
        }
        log.info("远控流程已启动");
    }

    /**
     * 停止流程。
     */
    public void stop() {
        if (client != null) {
            client.disconnect();
        }
        if (server != null) {
            server.stop();
        }
        log.info("远控流程已停止");
    }

    /**
     * 获取底层服务端引擎（服务端模式）。
     *
     * @return 帧服务端，客户端模式返回 null
     */
    public FrameServer getServer() {
        return server;
    }

    /**
     * 获取底层客户端引擎（客户端模式）。
     *
     * @return 帧客户端，服务端模式返回 null
     */
    public FrameClient getClient() {
        return client;
    }
}
