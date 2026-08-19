package com.chua.common.support.network.sync.netty;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlow;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;

import java.util.*;

/**
 * Netty HTTP 同步流程管理器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NettyHttpSyncFlow implements SyncFlow {

    /** 客户端 */
    private final NettyHttpSyncClient client;
    /** 服务器 */
    private final NettyHttpSyncServer server;
    /** running */
    private volatile boolean running = false;
    /** Listeners */
    private final List<SyncFlowListener> listeners = new ArrayList<>();

    /**
     * 创建 NettyHttpSyncFlow 实例
     * @param setting setting
     * @param String String
     */
    public NettyHttpSyncFlow(com.chua.common.support.network.server.ServerSetting setting, String serverUrl) {
        this.server = new NettyHttpSyncServer(setting);
        this.client = new NettyHttpSyncClient(serverUrl);
    }

    /**
     * 创建 NettyHttpSyncFlow 实例
     * @param serverUrl serverUrl
     */
    public NettyHttpSyncFlow(String serverUrl) {
        this.server = null;
        this.client = new NettyHttpSyncClient(serverUrl);
    }

    /**
     * 创建 NettyHttpSyncFlow 实例
     * @param setting setting
     */
    public NettyHttpSyncFlow(com.chua.common.support.network.server.ServerSetting setting) {
        this.server = new NettyHttpSyncServer(setting);
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
