package com.chua.rsocket.support.sync;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlow;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.server.SyncServer;

import java.util.*;

/**
 * RSocket 同步流程管理器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RSocketSyncFlow implements SyncFlow {

    /**
     * 客户端实例
     */
    private final RSocketSyncClient client;
    /**
     * 服务器实例
     */
    private final RSocketSyncServer server;
    /**
     * running
     */
    private volatile boolean running = false;
    /**
     * 监听器列表
     */
    private final List<SyncFlowListener> listeners = new ArrayList<>();

    /**
     * 创建 RSocketSyncFlow 实例
     * @param setting setting
     * @param String String
     */
    public RSocketSyncFlow(com.chua.common.support.network.server.ServerSetting setting, String serverUrl) {
        this.server = new RSocketSyncServer(setting);
        this.client = new RSocketSyncClient(serverUrl);
    }

    /**
     * 创建 RSocketSyncFlow 实例
     * @param serverUrl serverUrl
     */
    public RSocketSyncFlow(String serverUrl) {
        this.server = null;
        this.client = new RSocketSyncClient(serverUrl);
    }

    /**
     * 创建 RSocketSyncFlow 实例
     * @param setting setting
     */
    public RSocketSyncFlow(com.chua.common.support.network.server.ServerSetting setting) {
        this.server = new RSocketSyncServer(setting);
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
            server.addListener(new com.chua.common.support.network.server.SyncServerListener() {
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

    /** 通知Listeners */
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
