package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlow;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.server.ServerSetting;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 同步流程管理器。
 * <p>
 * 组合 {@link HttpSyncClient} 和 {@link HttpSyncServer}，提供统一的生命周期管理。
 * </p>
 *
 * @author CH
 * @since 2026-07-25
 */
public class HttpSyncFlow implements SyncFlow {

    /**
     * HTTP 同步客户端
     */
    private final HttpSyncClient client;

    /**
     * HTTP 同步服务端
     */
    private final HttpSyncServer server;

    /**
     * 是否运行中
     */
    private volatile boolean running = false;

    /**
     * 监听器列表
     */
    private final java.util.List<SyncFlowListener> listeners = new java.util.ArrayList<>();

    /**
     * 创建 HTTP 同步流程。
     *
     * @param setting 服务端配置
     * @param serverUrl 客户端连接的服务端地址
     */
    public HttpSyncFlow(ServerSetting setting, String serverUrl) {
        this.server = new HttpSyncServer(setting);
        this.client = new HttpSyncClient(serverUrl);
    }

    /**
     * 创建 HTTP 同步流程（仅客户端模式）。
     *
     * @param serverUrl 服务端地址
     */
    public HttpSyncFlow(String serverUrl) {
        this.server = null;
        this.client = new HttpSyncClient(serverUrl);
    }

    /**
     * 创建 HTTP 同步流程（仅服务端模式）。
     *
     * @param setting 服务端配置
     */
    public HttpSyncFlow(ServerSetting setting) {
        this.server = new HttpSyncServer(setting);
        this.client = null;
    }

    @Override
    /** 开始 */
    public void start() {
        if (running) {
            return;
        }
        if (server != null) {
            server.start();
        }
        if (client != null) {
            client.connect();
        }
        running = true;
        notifyListeners(SyncFlowListener::onStart);
    }

    @Override
    /** 停止 */
    public void stop() {
        if (!running) {
            return;
        }
        if (client != null) {
            client.disconnect();
        }
        if (server != null) {
            server.stop();
        }
        running = false;
        notifyListeners(SyncFlowListener::onStop);
    }

    @Override
    /** 是否Running */
    public boolean isRunning() {
        return running;
    }

    @Override
    /** 获取Server */
    public SyncServer getServer() {
        return server;
    }

    @Override
    /** 获取Client */
    public SyncClient getClient() {
        return client;
    }

    @Override
    /** 添加Listener */
    public void addListener(SyncFlowListener listener) {
        listeners.add(listener);
        if (server != null) {
            server.addListener(new SyncServerListener() {
                @Override
                /** OnClientConnected */
                public void onClientConnected(String clientId, Map<String, Object> metadata) {
                    listener.onClientConnected(clientId);
                }

                @Override
                /** OnClientDisconnected */
                public void onClientDisconnected(String clientId) {
                    listener.onClientDisconnected(clientId);
                }

                @Override
                /** OnMessage */
                public void onMessage(String clientId, String topic, Object message) {
                    listener.onMessage(topic, message);
                }

                @Override
                /** On记录错误 */
                public void onError(String clientId, Throwable cause) {
                    listener.onError(clientId, cause);
                }
            });
        }
        if (client != null) {
            client.addListener(listener);
        }
    }

    @Override
    /** 移除Listener */
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
        if (client != null) {
            client.removeListener(listener);
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        stop();
    }

    // ==================== 内部方法 ====================

    /**
     * 通知监听器
     * @param action 方法入参 action
     */
    private void notifyListeners(java.util.function.Consumer<SyncFlowListener> action) {
        for (SyncFlowListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
