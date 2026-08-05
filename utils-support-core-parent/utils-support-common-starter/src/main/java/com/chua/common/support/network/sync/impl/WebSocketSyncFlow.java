package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlow;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;

import java.util.*;

/**
 * WebSocket 同步流程管理器。
 * <p>
 * 组合 {@link WebSocketSyncClient} 和 {@link WebSocketSyncServer}，提供统一的生命周期管理。
 * </p>
 *
 * @author CH
 * @since 2026-07-25
 */
public class WebSocketSyncFlow implements SyncFlow {

    /**
     * WebSocket 同步客户端
     */
    private final WebSocketSyncClient client;

    /**
     * WebSocket 同步服务端
     */
    private final WebSocketSyncServer server;

    /**
     * 是否运行中
     */
    private volatile boolean running = false;

    /**
     * 监听器列表
     */
    private final List<SyncFlowListener> listeners = new ArrayList<>();

    /**
     * 创建 WebSocket 同步流程。
     *
     * @param setting 服务端配置
     * @param serverUrl 客户端连接的服务端地址
     */
    public WebSocketSyncFlow(ServerSetting setting, String serverUrl) {
        this.server = new WebSocketSyncServer(setting);
        this.client = new WebSocketSyncClient(serverUrl);
    }

    /**
     * 创建 WebSocket 同步流程（仅客户端模式）。
     *
     * @param serverUrl 服务端地址
     */
    public WebSocketSyncFlow(String serverUrl) {
        this.server = null;
        this.client = new WebSocketSyncClient(serverUrl);
    }

    /**
     * 创建 WebSocket 同步流程（仅服务端模式）。
     *
     * @param setting 服务端配置
     */
    public WebSocketSyncFlow(ServerSetting setting) {
        this.server = new WebSocketSyncServer(setting);
        this.client = null;
    }

    @Override
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
    public boolean isRunning() {
        return running;
    }

    @Override
    public SyncServer getServer() {
        return server;
    }

    @Override
    public SyncClient getClient() {
        return client;
    }

    @Override
    public void addListener(SyncFlowListener listener) {
        listeners.add(listener);
        if (server != null) {
            server.addListener(new SyncServerListener() {
                @Override
                public void onClientConnected(String clientId, Map<String, Object> metadata) {
                    listener.onClientConnected(clientId);
                }

                @Override
                public void onClientDisconnected(String clientId) {
                    listener.onClientDisconnected(clientId);
                }

                @Override
                public void onMessage(String clientId, String topic, Object message) {
                    listener.onMessage(topic, message);
                }

                @Override
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
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
        if (client != null) {
            client.removeListener(listener);
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ==================== 内部方法 ====================

    /**
     * 通知监听器
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
