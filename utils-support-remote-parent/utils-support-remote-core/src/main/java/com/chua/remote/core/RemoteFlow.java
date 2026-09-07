package com.chua.remote.core;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

/**
 * 远控流程管理器。
 *
 * <p>同时管理网关服务端和客户端（TCP 同步族，经 SPI 加载 {@code tcp} 扩展），适用于网关既作为
 * 服务端接收被控端连接，又作为客户端连接控制端的场景。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RemoteFlow {

    /** 服务端（服务端模式）——经 SPI 加载的 TCP 同步服务端 */
    private final SyncServer server;

    /** 客户端（客户端模式）——经 SPI 加载的 TCP 同步客户端 */
    private final SyncClient client;

    /**
     * 创建流程管理器（纯客户端模式）。
     *
     * @param serverUrl 服务端地址
     */
    public RemoteFlow(String serverUrl) {
        this.server = null;
        this.client = ServiceProvider.of(SyncClient.class).getNewExtension("tcp", serverUrl);
    }

    /**
     * 创建流程管理器（服务端模式）。
     *
     * @param setting 服务配置
     */
    public RemoteFlow(ServerSetting setting) {
        this.server = ServiceProvider.of(SyncServer.class).getNewExtension("tcp", setting);
        this.client = null;
    }

    /**
     * 创建流程管理器（同时启动服务端和客户端）。
     *
     * @param setting   服务配置
     * @param serverUrl 客户端连接地址
     */
    public RemoteFlow(ServerSetting setting, String serverUrl) {
        this.server = ServiceProvider.of(SyncServer.class).getNewExtension("tcp", setting);
        this.client = ServiceProvider.of(SyncClient.class).getNewExtension("tcp", serverUrl);
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
        if (server != null) {
            server.stop();
        }
        if (client != null) {
            client.disconnect();
        }
        log.info("远控流程已停止");
    }

    /**
     * 获取底层服务端（服务端模式）。
     *
     * @return TCP 同步服务端，客户端模式返回 null
     */
    public SyncServer getServer() {
        return server;
    }

    /**
     * 获取底层客户端（客户端模式）。
     *
     * @return TCP 同步客户端，服务端模式返回 null
     */
    public SyncClient getClient() {
        return client;
    }
}
